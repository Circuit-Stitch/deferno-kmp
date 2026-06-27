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
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recurring-definition write path (#299): [OutboxDefinitionWriter] applies each "light switch" set
 * optimistically to the correct per-kind local cache (so the Item tree reflects it instantly) and enqueues
 * its idempotent `PATCH {kind}/{id}` request for replay. The recurring-kind mirror of `OutboxTaskWriterTest`,
 * run against the in-memory fakes (ADR-0006 JVM-fast path).
 */
class OutboxDefinitionWriterTest {

    private val now = Instant.parse("2026-06-07T12:00:00Z")
    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun habit(id: String, state: DefinitionState = DefinitionState.Active) = Habit(
        id = HabitId(id), orgSlug = "u-test", title = "habit-$id", definitionState = state,
        dateCreated = created, hydration = HydrationState.Summary,
    )

    private fun chore(id: String, state: DefinitionState = DefinitionState.Active) = Chore(
        id = ChoreId(id), orgSlug = "u-test", title = "chore-$id", definitionState = state,
        dateCreated = created, hydration = HydrationState.Summary,
    )

    private fun event(id: String, state: DefinitionState = DefinitionState.Active) = Event(
        id = EventId(id), orgSlug = "u-test", title = "event-$id", definitionState = state,
        dateCreated = created, hydration = HydrationState.Summary,
    )

    private fun writer(
        habits: FakeHabitLocalStore = FakeHabitLocalStore(),
        chores: FakeChoreLocalStore = FakeChoreLocalStore(),
        events: FakeEventLocalStore = FakeEventLocalStore(),
        outbox: FakeOutboxStore = FakeOutboxStore(),
    ) = OutboxDefinitionWriter(habits, chores, events, outbox, now = { now })

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
    }

    @Test
    fun eventArchiveAppliesToTheEventStore() = runTest {
        val events = FakeEventLocalStore(mapOf(EventId("e") to event("e")))
        val outbox = FakeOutboxStore()

        writer(events = events, outbox = outbox).setDefinitionState("e", ItemKind.Event, DefinitionState.Archived)

        assertEquals(DefinitionState.Archived, events.all.getValue(EventId("e")).definitionState)
        assertEquals(listOf("events", "e"), outbox.all.single().request.path)
    }

    @Test
    fun aWriteToAnAbsentRowSkipsTheApplyButStillEnqueues() = runTest {
        val habits = FakeHabitLocalStore() // empty
        val outbox = FakeOutboxStore()

        writer(habits = habits, outbox = outbox).setDefinitionState("ghost", ItemKind.Habit, DefinitionState.Archived)

        assertTrue(habits.all.isEmpty(), "no phantom row materialised")
        assertEquals(1, outbox.all.size, "the write is not lost — it reconciles on replay")
        assertEquals("""{"status":"archived"}""", outbox.all.single().request.body)
    }
}
