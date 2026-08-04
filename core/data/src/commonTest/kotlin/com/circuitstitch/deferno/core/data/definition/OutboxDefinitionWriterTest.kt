package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recurring-definition write path (#299, widened by #378): [OutboxDefinitionWriter] applies each
 * edit optimistically to the correct per-kind local cache (so the Item tree reflects it instantly),
 * captures the pre-apply before-image for the Activity diff, and enqueues its idempotent
 * `PATCH {kind}/{id}` request for replay. The recurring-kind mirror of `OutboxTaskWriterTest`, run
 * against the in-memory fakes (ADR-0006 JVM-fast path).
 *
 * The three verbs are asserted per kind because the store dispatch and the endpoint are both
 * kind-selected: a kind wired to the wrong store would corrupt a neighbouring cache, and one wired to
 * the wrong route would drain as a `404` — which the sender classifies Success, so the write would
 * vanish with nothing to observe.
 */
class OutboxDefinitionWriterTest {

    private val now = Instant.parse("2026-06-07T12:00:00Z")
    private val created = Instant.parse("2026-05-20T16:11:42Z")
    private val wanted = Instant.parse("2026-06-05T09:00:00Z")
    private val deadline = Instant.parse("2026-06-08T17:00:00Z")

    private fun habit(
        id: String,
        state: DefinitionState = DefinitionState.Active,
        targetDate: Instant? = null,
        priority: Priority = Priority.Normal,
        completeBy: Instant? = null,
    ) = Habit(
        id = HabitId(id), orgSlug = "u-test", title = "habit-$id", definitionState = state,
        targetDate = targetDate, priority = priority, completeBy = completeBy,
        dateCreated = created, hydration = HydrationState.Summary,
    )

    private fun chore(
        id: String,
        state: DefinitionState = DefinitionState.Active,
        targetDate: Instant? = null,
        priority: Priority = Priority.Normal,
    ) = Chore(
        id = ChoreId(id), orgSlug = "u-test", title = "chore-$id", definitionState = state,
        targetDate = targetDate, priority = priority,
        dateCreated = created, hydration = HydrationState.Summary,
    )

    private fun event(
        id: String,
        state: DefinitionState = DefinitionState.Active,
        targetDate: Instant? = null,
        priority: Priority = Priority.Normal,
    ) = Event(
        id = EventId(id), orgSlug = "u-test", title = "event-$id", definitionState = state,
        targetDate = targetDate, priority = priority,
        dateCreated = created, hydration = HydrationState.Summary,
    )

    private fun writer(
        habits: FakeHabitLocalStore = FakeHabitLocalStore(),
        chores: FakeChoreLocalStore = FakeChoreLocalStore(),
        events: FakeEventLocalStore = FakeEventLocalStore(),
        outbox: FakeOutboxStore = FakeOutboxStore(),
    ) = OutboxDefinitionWriter(habits, chores, events, outbox, now = { now })

    // --- setDefinitionState: the "light switch" (#299) ---

    @Test
    fun habitArchiveAppliesOptimisticallyAndEnqueuesTheStatusPatch() = runTest {
        val habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h")))
        val outbox = FakeOutboxStore()

        writer(habits = habits, outbox = outbox).setDefinitionState("h", ItemKind.Habit, DefinitionState.Archived)

        // Optimistic local apply — visible immediately, before any network.
        assertEquals(DefinitionState.Archived, habits.all.getValue(HabitId("h")).definitionState)
        // Enqueued, ready to dispatch now.
        val entry = outbox.all.single()
        assertEquals("item:h", entry.target)
        assertEquals(OutboxMethod.Patch, entry.request.method)
        assertEquals(listOf("habits", "h"), entry.request.path)
        assertEquals("""{"status":"archived"}""", entry.request.body)
        assertEquals(now, entry.nextAttemptAt)
        // The Activity diff's old half, snapshotted from the row before the apply overwrote it (#378).
        // Until this landed the Trail rendered "status: (unavailable) → archived" for every archive.
        assertEquals("""{"status":"active"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun choreRestoreAppliesToTheChoreStoreOnly() = runTest {
        val chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c", DefinitionState.Archived)))
        val habits = FakeHabitLocalStore()
        val outbox = FakeOutboxStore()

        writer(habits = habits, chores = chores, outbox = outbox)
            .setDefinitionState("c", ItemKind.Chore, DefinitionState.Active)

        assertEquals(DefinitionState.Active, chores.all.getValue(ChoreId("c")).definitionState)
        assertTrue(habits.all.isEmpty(), "a chore write must not touch the habit store")
        assertEquals(listOf("chores", "c"), outbox.all.single().request.path)
        assertEquals("""{"status":"active"}""", outbox.all.single().request.body)
        assertEquals("""{"status":"archived"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun eventArchiveAppliesToTheEventStore() = runTest {
        val events = FakeEventLocalStore(mapOf(EventId("e") to event("e")))
        val outbox = FakeOutboxStore()

        writer(events = events, outbox = outbox).setDefinitionState("e", ItemKind.Event, DefinitionState.Archived)

        assertEquals(DefinitionState.Archived, events.all.getValue(EventId("e")).definitionState)
        assertEquals(listOf("events", "e"), outbox.all.single().request.path)
        assertEquals("""{"status":"active"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun aWriteToAnAbsentRowSkipsTheApplyButStillEnqueues() = runTest {
        val habits = FakeHabitLocalStore() // empty
        val outbox = FakeOutboxStore()

        writer(habits = habits, outbox = outbox).setDefinitionState("ghost", ItemKind.Habit, DefinitionState.Archived)

        assertTrue(habits.all.isEmpty(), "no phantom row materialised")
        assertEquals(1, outbox.all.size, "the write is not lost — it reconciles on replay")
        assertEquals("""{"status":"archived"}""", outbox.all.single().request.body)
        // Nothing was cached, so there is no old value to claim — the ledger renders "previously
        // unavailable" rather than inventing one.
        assertNull(outbox.enqueuedBefore.single())
    }

    // --- setTargetDate: the soft date (#375/#378) ---

    @Test
    fun habitTargetDateAppliesOptimisticallyAndEnqueuesTheDatePatch() = runTest {
        val habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", targetDate = deadline)))
        val outbox = FakeOutboxStore()

        writer(habits = habits, outbox = outbox).setTargetDate("h", ItemKind.Habit, wanted)

        assertEquals(wanted, habits.all.getValue(HabitId("h")).targetDate)
        val entry = outbox.all.single()
        assertEquals("item:h", entry.target)
        assertEquals(OutboxMethod.Patch, entry.request.method)
        assertEquals(listOf("habits", "h"), entry.request.path)
        assertEquals("""{"target_date":"2026-06-05T09:00:00Z"}""", entry.request.body)
        assertEquals("""{"target_date":"2026-06-08T17:00:00Z"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun choreTargetDateClearAppliesToTheChoreStoreOnly() = runTest {
        val chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c", targetDate = wanted)))
        val events = FakeEventLocalStore()
        val outbox = FakeOutboxStore()

        writer(chores = chores, events = events, outbox = outbox).setTargetDate("c", ItemKind.Chore, null)

        assertNull(chores.all.getValue(ChoreId("c")).targetDate)
        assertTrue(events.all.isEmpty(), "a chore write must not touch the event store")
        assertEquals(listOf("chores", "c"), outbox.all.single().request.path)
        assertEquals("""{"target_date":null}""", outbox.all.single().request.body)
    }

    @Test
    fun eventTargetDateAppliesToTheEventStore() = runTest {
        val events = FakeEventLocalStore(mapOf(EventId("e") to event("e")))
        val outbox = FakeOutboxStore()

        writer(events = events, outbox = outbox).setTargetDate("e", ItemKind.Event, wanted)

        assertEquals(wanted, events.all.getValue(EventId("e")).targetDate)
        assertEquals(listOf("events", "e"), outbox.all.single().request.path)
        // No old date to diff against — an explicit null, the same key the body carries.
        assertEquals("""{"target_date":null}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun aLapsedDefinitionStoresTheClampedDateNotTheOneRequested() = runTest {
        // The commonest recurring shape: complete_by is a moving cursor that is routinely in the past.
        // The server clamps a later target down to it (#629) and answers 200, so an unclamped optimistic
        // row would show a date the server never stored and the control would appear to do nothing.
        val habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", completeBy = deadline)))
        val outbox = FakeOutboxStore()
        val requested = Instant.parse("2026-06-30T09:00:00Z")

        writer(habits = habits, outbox = outbox).setTargetDate("h", ItemKind.Habit, requested)

        assertEquals(deadline, habits.all.getValue(HabitId("h")).targetDate)
        // The wire value stays raw: the server clamps against its own authoritative deadline.
        assertEquals("""{"target_date":"2026-06-30T09:00:00Z"}""", outbox.all.single().request.body)
    }

    @Test
    fun aTargetDateWriteToAnAbsentRowSkipsTheApplyButStillEnqueues() = runTest {
        val habits = FakeHabitLocalStore()
        val outbox = FakeOutboxStore()

        writer(habits = habits, outbox = outbox).setTargetDate("ghost", ItemKind.Habit, wanted)

        assertTrue(habits.all.isEmpty(), "no phantom row materialised")
        assertEquals("""{"target_date":"2026-06-05T09:00:00Z"}""", outbox.all.single().request.body)
        assertNull(outbox.enqueuedBefore.single())
    }

    // --- setPriority: the urgency bucket (#375/#378) ---

    @Test
    fun habitPriorityAppliesOptimisticallyAndEnqueuesThePriorityPatch() = runTest {
        val habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", priority = Priority.Normal)))
        val outbox = FakeOutboxStore()

        writer(habits = habits, outbox = outbox).setPriority("h", ItemKind.Habit, Priority.Fire)

        assertEquals(Priority.Fire, habits.all.getValue(HabitId("h")).priority)
        val entry = outbox.all.single()
        assertEquals(listOf("habits", "h"), entry.request.path)
        assertEquals("""{"priority":"fire"}""", entry.request.body)
        assertEquals("""{"priority":"normal"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun chorePriorityAppliesToTheChoreStoreOnly() = runTest {
        val chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c", priority = Priority.Fire)))
        val habits = FakeHabitLocalStore()
        val outbox = FakeOutboxStore()

        writer(habits = habits, chores = chores, outbox = outbox).setPriority("c", ItemKind.Chore, Priority.Backlog)

        assertEquals(Priority.Backlog, chores.all.getValue(ChoreId("c")).priority)
        assertTrue(habits.all.isEmpty(), "a chore write must not touch the habit store")
        assertEquals(listOf("chores", "c"), outbox.all.single().request.path)
        assertEquals("""{"priority":"backlog"}""", outbox.all.single().request.body)
        assertEquals("""{"priority":"fire"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun eventPriorityAppliesToTheEventStore() = runTest {
        val events = FakeEventLocalStore(mapOf(EventId("e") to event("e")))
        val outbox = FakeOutboxStore()

        writer(events = events, outbox = outbox).setPriority("e", ItemKind.Event, Priority.Backlog)

        assertEquals(Priority.Backlog, events.all.getValue(EventId("e")).priority)
        assertEquals(listOf("events", "e"), outbox.all.single().request.path)
        assertEquals("""{"priority":"backlog"}""", outbox.all.single().request.body)
    }

    @Test
    fun aPriorityWriteToAnAbsentRowSkipsTheApplyButStillEnqueues() = runTest {
        val events = FakeEventLocalStore()
        val outbox = FakeOutboxStore()

        writer(events = events, outbox = outbox).setPriority("ghost", ItemKind.Event, Priority.Fire)

        assertTrue(events.all.isEmpty(), "no phantom row materialised")
        assertEquals("""{"priority":"fire"}""", outbox.all.single().request.body)
        assertNull(outbox.enqueuedBefore.single())
    }

    // --- The shared dispatch's guard + its blast radius ---

    @Test
    fun everyVerbRejectsATaskWithoutEnqueueingAnything() = runTest {
        val outbox = FakeOutboxStore()
        val writer = writer(outbox = outbox)

        assertFailsWith<IllegalStateException> { writer.setDefinitionState("t", ItemKind.Task, DefinitionState.Archived) }
        assertFailsWith<IllegalStateException> { writer.setTargetDate("t", ItemKind.Task, wanted) }
        assertFailsWith<IllegalStateException> { writer.setPriority("t", ItemKind.Task, Priority.Fire) }
        // The guard fires before the enqueue, so a rejected call leaves no queued write behind.
        assertTrue(outbox.all.isEmpty(), "a rejected write must not reach the queue")
    }

    @Test
    fun eachVerbRewritesOnlyItsOwnFieldOnTheCachedRow() = runTest {
        // The shared dispatch lowers a whole DefinitionFields back onto the row, so the projection's
        // untouched members have to round-trip: a verb that quietly reset a neighbour would be invisible
        // to the per-verb assertions above.
        val habits = FakeHabitLocalStore(
            mapOf(HabitId("h") to habit("h", state = DefinitionState.InReview, targetDate = wanted, priority = Priority.Fire)),
        )

        writer(habits = habits).setPriority("h", ItemKind.Habit, Priority.Backlog)

        val row = habits.all.getValue(HabitId("h"))
        assertEquals(Priority.Backlog, row.priority)
        assertEquals(DefinitionState.InReview, row.definitionState)
        assertEquals(wanted, row.targetDate)
        assertEquals("habit-h", row.title)
    }
}
