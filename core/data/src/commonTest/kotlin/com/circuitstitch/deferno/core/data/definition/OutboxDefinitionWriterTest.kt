package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.data.item.FakeItemLocalStore
import com.circuitstitch.deferno.core.data.item.cacheOf
import com.circuitstitch.deferno.core.data.item.cached
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
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recurring-definition write path (#299, widened by #378): [OutboxDefinitionWriter] applies each
 * edit optimistically to the cached record (so the Item tree reflects it instantly), captures the
 * pre-apply before-image for the Activity diff, and enqueues its idempotent `PATCH {kind}/{id}` request
 * for replay. The recurring-kind mirror of `OutboxTaskWriterTest`, run against the in-memory fake
 * (ADR-0006 JVM-fast path).
 *
 * The three verbs are still asserted **per kind**, because the endpoint is kind-selected and a write on
 * the wrong route drains as a `404` — which the sender classifies Success, so the write would vanish
 * with nothing to observe. What is no longer asserted per kind is the store: there is one (#422), and
 * with it went the `when (kind)` dispatch and the hand-rolled `DefinitionFields` projection that existed
 * so the dispatch could be written once rather than once per verb.
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
        items: FakeItemLocalStore = FakeItemLocalStore(),
        outbox: FakeOutboxStore = FakeOutboxStore(),
    ) = OutboxDefinitionWriter(items, outbox, now = { now })

    /** The cached record for [id] — what each verb's optimistic transform rewrote. */
    private fun FakeItemLocalStore.row(id: String): Item = all.getValue(id).item

    /** This record's light switch, read the way the writer reads it. */
    private val Item.definitionState: DefinitionState?
        get() = (progress.lifecycle as? Lifecycle.Definition)?.state

    // --- setDefinitionState: the "light switch" (#299) ---

    @Test
    fun habitArchiveAppliesOptimisticallyAndEnqueuesTheStatusPatch() = runTest {
        val items = FakeItemLocalStore(cacheOf(habit("h").cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).setDefinitionState("h", ItemKind.Habit, DefinitionState.Archived)

        // Optimistic local apply — visible immediately, before any network.
        assertEquals(DefinitionState.Archived, items.row("h").definitionState)
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
    fun choreRestoreRewritesTheAddressedRowAlone() = runTest {
        val items = FakeItemLocalStore(
            cacheOf(chore("c", DefinitionState.Archived).cached(), habit("h", DefinitionState.Archived).cached()),
        )
        val outbox = FakeOutboxStore()

        writer(items, outbox).setDefinitionState("c", ItemKind.Chore, DefinitionState.Active)

        assertEquals(DefinitionState.Active, items.row("c").definitionState)
        // One cache means "the right store" is no longer a question, but "the right row" still is.
        assertEquals(DefinitionState.Archived, items.row("h").definitionState, "a neighbouring row must not move")
        assertEquals(listOf("chores", "c"), outbox.all.single().request.path)
        assertEquals("""{"status":"active"}""", outbox.all.single().request.body)
        assertEquals("""{"status":"archived"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun eventArchiveUsesTheEventEndpoint() = runTest {
        val items = FakeItemLocalStore(cacheOf(event("e").cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).setDefinitionState("e", ItemKind.Event, DefinitionState.Archived)

        assertEquals(DefinitionState.Archived, items.row("e").definitionState)
        assertEquals(listOf("events", "e"), outbox.all.single().request.path)
        assertEquals("""{"status":"active"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun aWriteToAnAbsentRowSkipsTheApplyButStillEnqueues() = runTest {
        val items = FakeItemLocalStore() // empty
        val outbox = FakeOutboxStore()

        writer(items, outbox).setDefinitionState("ghost", ItemKind.Habit, DefinitionState.Archived)

        assertTrue(items.all.isEmpty(), "no phantom row materialised")
        assertEquals(1, outbox.all.size, "the write is not lost — it reconciles on replay")
        assertEquals("""{"status":"archived"}""", outbox.all.single().request.body)
        // Nothing was cached, so there is no old value to claim — the ledger renders "previously
        // unavailable" rather than inventing one.
        assertNull(outbox.enqueuedBefore.single())
    }

    // --- setTargetDate: the soft date (#375/#378) ---

    @Test
    fun habitTargetDateAppliesOptimisticallyAndEnqueuesTheDatePatch() = runTest {
        val items = FakeItemLocalStore(cacheOf(habit("h", targetDate = deadline).cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).setTargetDate("h", ItemKind.Habit, wanted)

        assertEquals(wanted, items.row("h").targeted.targetDate)
        val entry = outbox.all.single()
        assertEquals("item:h", entry.target)
        assertEquals(OutboxMethod.Patch, entry.request.method)
        assertEquals(listOf("habits", "h"), entry.request.path)
        assertEquals("""{"target_date":"2026-06-05T09:00:00Z"}""", entry.request.body)
        assertEquals("""{"target_date":"2026-06-08T17:00:00Z"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun choreTargetDateClearRewritesTheAddressedRowAlone() = runTest {
        val items = FakeItemLocalStore(
            cacheOf(chore("c", targetDate = wanted).cached(), event("e", targetDate = wanted).cached()),
        )
        val outbox = FakeOutboxStore()

        writer(items, outbox).setTargetDate("c", ItemKind.Chore, null)

        assertNull(items.row("c").targeted.targetDate)
        assertEquals(wanted, items.row("e").targeted.targetDate, "a neighbouring row must not move")
        assertEquals(listOf("chores", "c"), outbox.all.single().request.path)
        assertEquals("""{"target_date":null}""", outbox.all.single().request.body)
    }

    @Test
    fun eventTargetDateUsesTheEventEndpoint() = runTest {
        val items = FakeItemLocalStore(cacheOf(event("e").cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).setTargetDate("e", ItemKind.Event, wanted)

        assertEquals(wanted, items.row("e").targeted.targetDate)
        assertEquals(listOf("events", "e"), outbox.all.single().request.path)
        // No old date to diff against — an explicit null, the same key the body carries.
        assertEquals("""{"target_date":null}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun aLapsedDefinitionStoresTheClampedDateNotTheOneRequested() = runTest {
        // The commonest recurring shape: complete_by is a moving cursor that is routinely in the past.
        // The server clamps a later target down to it (#629) and answers 200, so an unclamped optimistic
        // row would show a date the server never stored and the control would appear to do nothing.
        val items = FakeItemLocalStore(cacheOf(habit("h", completeBy = deadline).cached()))
        val outbox = FakeOutboxStore()
        val requested = Instant.parse("2026-06-30T09:00:00Z")

        writer(items, outbox).setTargetDate("h", ItemKind.Habit, requested)

        assertEquals(deadline, items.row("h").targeted.targetDate)
        // The wire value stays raw: the server clamps against its own authoritative deadline.
        assertEquals("""{"target_date":"2026-06-30T09:00:00Z"}""", outbox.all.single().request.body)
    }

    @Test
    fun aTargetDateWriteToAnAbsentRowSkipsTheApplyButStillEnqueues() = runTest {
        val items = FakeItemLocalStore()
        val outbox = FakeOutboxStore()

        writer(items, outbox).setTargetDate("ghost", ItemKind.Habit, wanted)

        assertTrue(items.all.isEmpty(), "no phantom row materialised")
        assertEquals("""{"target_date":"2026-06-05T09:00:00Z"}""", outbox.all.single().request.body)
        assertNull(outbox.enqueuedBefore.single())
    }

    // --- setPriority: the urgency bucket (#375/#378) ---

    @Test
    fun habitPriorityAppliesOptimisticallyAndEnqueuesThePriorityPatch() = runTest {
        val items = FakeItemLocalStore(cacheOf(habit("h", priority = Priority.Normal).cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).setPriority("h", ItemKind.Habit, Priority.Fire)

        assertEquals(Priority.Fire, items.row("h").priority.priority)
        val entry = outbox.all.single()
        assertEquals(listOf("habits", "h"), entry.request.path)
        assertEquals("""{"priority":"fire"}""", entry.request.body)
        assertEquals("""{"priority":"normal"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun chorePriorityRewritesTheAddressedRowAlone() = runTest {
        val items = FakeItemLocalStore(
            cacheOf(chore("c", priority = Priority.Fire).cached(), habit("h", priority = Priority.Fire).cached()),
        )
        val outbox = FakeOutboxStore()

        writer(items, outbox).setPriority("c", ItemKind.Chore, Priority.Backlog)

        assertEquals(Priority.Backlog, items.row("c").priority.priority)
        assertEquals(Priority.Fire, items.row("h").priority.priority, "a neighbouring row must not move")
        assertEquals(listOf("chores", "c"), outbox.all.single().request.path)
        assertEquals("""{"priority":"backlog"}""", outbox.all.single().request.body)
        assertEquals("""{"priority":"fire"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun eventPriorityUsesTheEventEndpoint() = runTest {
        val items = FakeItemLocalStore(cacheOf(event("e").cached()))
        val outbox = FakeOutboxStore()

        writer(items, outbox).setPriority("e", ItemKind.Event, Priority.Backlog)

        assertEquals(Priority.Backlog, items.row("e").priority.priority)
        assertEquals(listOf("events", "e"), outbox.all.single().request.path)
        assertEquals("""{"priority":"backlog"}""", outbox.all.single().request.body)
    }

    @Test
    fun aPriorityWriteToAnAbsentRowSkipsTheApplyButStillEnqueues() = runTest {
        val items = FakeItemLocalStore()
        val outbox = FakeOutboxStore()

        writer(items, outbox).setPriority("ghost", ItemKind.Event, Priority.Fire)

        assertTrue(items.all.isEmpty(), "no phantom row materialised")
        assertEquals("""{"priority":"fire"}""", outbox.all.single().request.body)
        assertNull(outbox.enqueuedBefore.single())
    }

    // --- The shared dispatch's guard + its blast radius ---

    @Test
    fun everyVerbRejectsATaskWithoutEnqueueingAnything() = runTest {
        val outbox = FakeOutboxStore()
        val writer = writer(outbox = outbox)

        // `ItemKind.recurringPath()` refuses first, while the request is being built — before the
        // writer's own `require` is even reached, since the request is an argument to `submit`.
        assertFailsWith<IllegalStateException> { writer.setDefinitionState("t", ItemKind.Task, DefinitionState.Archived) }
        assertFailsWith<IllegalStateException> { writer.setTargetDate("t", ItemKind.Task, wanted) }
        assertFailsWith<IllegalStateException> { writer.setPriority("t", ItemKind.Task, Priority.Fire) }
        // The guard fires before the enqueue, so a rejected call leaves no queued write behind.
        assertTrue(outbox.all.isEmpty(), "a rejected write must not reach the queue")
    }

    @Test
    fun eachVerbSwapsOnlyItsOwnFamilyOnTheCachedRow() = runTest {
        // Each verb is a Family swap now, where it used to lower a whole `DefinitionFields` back onto the
        // row — so a verb that quietly reset a neighbouring field would be invisible to the per-verb
        // assertions above. The claim is asserted over the whole written-back row rather than field by
        // field, so a family nobody thought to name here is still covered.
        val before = habit("h", state = DefinitionState.InReview, targetDate = wanted, priority = Priority.Fire)
        val items = FakeItemLocalStore(cacheOf(before.cached()))

        writer(items).setPriority("h", ItemKind.Habit, Priority.Backlog)

        assertEquals(
            before.copy(priority = Priority.Backlog),
            ParityRecipe.writeHabit(items.row("h")),
        )
    }
}
