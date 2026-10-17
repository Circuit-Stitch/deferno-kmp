package com.circuitstitch.deferno.core.data.item

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cross-kind reconcile of [ItemSync] (ADR-0049, #226) — the heart of the `/tasks` -> `/items`
 * migration, run against the in-memory fake on the ADR-0006 JVM-fast path. Proves the cold `/items`
 * snapshot is reconciled into the one item cache (upsert + orphan-purge), that the server-windowed
 * snapshot honours the done-visibility window with no client-side window math, that offline creates are
 * protected from the purge, and that an unavailable pull leaves the cache intact.
 *
 * **One reconcile, where there were four (#422).** The snapshot still arrives as four typed lists,
 * because the wire still speaks four kinds, and each row crosses into the plugin-shaped record at that
 * boundary. What went with the four stores is the four separate transactions: a row whose kind changed
 * server-side used to be an insert in one table and an orphan purge in another, and nothing made those
 * two atomic.
 */
class ItemSyncTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun task(id: String, state: WorkingState = WorkingState.Open, deletedAt: Instant? = null) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = "task-$id",
        workingState = state,
        dateCreated = created,
        deletedAt = deletedAt,
        hydration = HydrationState.Full,
    )

    private fun habit(id: String) = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = "habit-$id",
        definitionState = DefinitionState.Active,
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private fun chore(id: String) = Chore(
        id = ChoreId(id),
        orgSlug = "u-e4h2qk",
        title = "chore-$id",
        definitionState = DefinitionState.Active,
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private fun event(id: String) = Event(
        id = EventId(id),
        orgSlug = "u-e4h2qk",
        title = "event-$id",
        definitionState = DefinitionState.Active,
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private class Fixture(
        val items: FakeItemLocalStore = FakeItemLocalStore(),
        val source: FakeItemSnapshotSource = FakeItemSnapshotSource(),
        val pending: FakePendingCreateStore = FakePendingCreateStore(),
    ) {
        val sync = ItemSync(items, source, pending)
    }

    // --- upsert: every kind into the one store, each noting which endpoint it came from ---

    @Test
    fun refreshUpsertsEveryKindIntoTheOneStore() = runTest {
        val f = Fixture()
        f.source.snapshot = ItemSnapshot(
            tasks = listOf(task("t")),
            habits = listOf(habit("h")),
            chores = listOf(chore("c")),
            events = listOf(event("e")),
        )

        f.sync.refresh()

        assertEquals(setOf("t", "h", "c", "e"), f.items.allIds())
        // The kind rides along as sync bookkeeping — which endpoint the row round-trips to — and is the
        // only thing that still distinguishes the four.
        assertEquals(
            mapOf("t" to ItemKind.Task, "h" to ItemKind.Habit, "c" to ItemKind.Chore, "e" to ItemKind.Event),
            f.items.all.mapValues { (_, row) -> row.kind },
        )
    }

    // --- orphan purge ---

    @Test
    fun refreshPurgesTheRowsAbsentFromTheSnapshot() = runTest {
        val f = Fixture(
            items = FakeItemLocalStore(
                cacheOf(task("keep").cached(), task("gone").cached(), habit("h-gone").cached()),
            ),
        )
        f.source.snapshot = ItemSnapshot(tasks = listOf(task("keep")), habits = listOf(habit("h-keep")))

        f.sync.refresh()

        assertEquals(setOf("keep", "h-keep"), f.items.allIds())
    }

    // --- AC3: the done-visibility window is honoured by the server-windowed snapshot (no client math) ---

    @Test
    fun aDoneTaskAgedOutOfTheWindowIsAbsentAfterRefreshWhileARecentlyDoneOneAndRecurringKindsRemain() = runTest {
        // Local cache holds a long-aged Done task, a recently-Done task, and a recurring habit.
        val f = Fixture(
            items = FakeItemLocalStore(
                cacheOf(
                    task("old-done", WorkingState.Done).cached(),
                    task("recent-done", WorkingState.Done).cached(),
                    habit("daily").cached(),
                ),
            ),
        )
        // The server applies the window: the long-aged Done task falls out of the snapshot; the
        // recently-Done one and the (never-aging) recurring habit stay. No client-side window math.
        f.source.snapshot = ItemSnapshot(
            tasks = listOf(task("recent-done", WorkingState.Done)),
            habits = listOf(habit("daily")),
        )

        f.sync.refresh()

        assertFalse(f.items.all.containsKey("old-done")) // aged out -> purged
        assertTrue(f.items.all.containsKey("recent-done")) // within window -> kept
        assertTrue(f.items.all.containsKey("daily")) // recurring -> never ages out
    }

    // --- offline creates are protected from the purge (#185), whatever their kind ---

    @Test
    fun refreshDoesNotPurgeAnOfflineCreatedRowStillAwaitingReplay() = runTest {
        val f = Fixture(
            items = FakeItemLocalStore(
                cacheOf(task("offline-task").cached(), habit("offline-habit").cached()),
            ),
        )
        f.pending.add("offline-task", ItemKind.Task)
        f.pending.add("offline-habit", ItemKind.Habit)
        f.source.snapshot = ItemSnapshot() // empty server snapshot: neither has replayed yet

        f.sync.refresh()

        assertEquals(setOf("offline-task", "offline-habit"), f.items.allIds())
    }

    // --- offline-first: an unavailable pull is a no-op ---

    @Test
    fun anUnavailablePullLeavesTheCacheIntact() = runTest {
        val f = Fixture(
            items = FakeItemLocalStore(
                cacheOf(task("t").cached(), habit("h").cached(), chore("c").cached(), event("e").cached()),
            ),
        )
        f.source.failNext = true // couldn't reach the server

        f.sync.refresh()

        assertEquals(setOf("t", "h", "c", "e"), f.items.allIds())
    }

    @Test
    fun aGenuinelyEmptyAvailableSnapshotPurgesEveryNonPendingRow() = runTest {
        val f = Fixture(
            items = FakeItemLocalStore(
                cacheOf(task("t").cached(), habit("h").cached(), chore("c").cached(), event("e").cached()),
            ),
        )
        f.source.snapshot = ItemSnapshot() // reachable, genuinely-empty server

        f.sync.refresh()

        assertTrue(f.items.allIds().isEmpty())
    }

    // --- a Full /items row replaces wholesale; a snapshot tombstone is kept ---

    @Test
    fun aFullSnapshotRowReplacesTheCachedRow() = runTest {
        val f = Fixture(items = FakeItemLocalStore(cacheOf(task("t", WorkingState.Open).cached())))
        f.source.snapshot = ItemSnapshot(tasks = listOf(task("t", WorkingState.Done).copy(title = "renamed")))

        f.sync.refresh()

        val row = f.items.all.getValue("t")
        assertEquals(Lifecycle.Working(WorkingState.Done), row.item.progress.lifecycle)
        assertEquals("renamed", row.item.core.title)
    }

    /**
     * A row whose kind changed server-side is one upsert in place, not a delete plus an insert (#422).
     * The four-store version could not express it as one act: the row arrived in the new kind's snapshot
     * list and vanished from the old kind's, so it was an insert in one table and an orphan purge in
     * another, across two transactions.
     */
    @Test
    fun aRowWhoseKindChangedServerSideIsUpsertedInPlace() = runTest {
        val f = Fixture(items = FakeItemLocalStore(cacheOf(task("x").cached())))
        f.source.snapshot = ItemSnapshot(habits = listOf(habit("x")))

        f.sync.refresh()

        assertEquals(setOf("x"), f.items.allIds())
        assertEquals(ItemKind.Habit, f.items.all.getValue("x").kind)
    }

    @Test
    fun aSnapshotTombstoneIsKeptAsADeletedRowExcludedFromObserve() = runTest {
        val f = Fixture()
        f.source.snapshot = ItemSnapshot(
            tasks = listOf(task("a"), task("gone", deletedAt = Instant.parse("2026-06-01T00:00:00Z"))),
        )

        f.sync.refresh()

        assertTrue(f.items.all.getValue("gone").item.core.isDeleted)
        f.items.observeActive().test {
            assertEquals(listOf("a"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- the reconcile commits as one transaction (single observe emission) ---

    @Test
    fun aReconcileReEmitsTheListOnceAtCommit() = runTest {
        val f = Fixture()
        f.items.observeActive().test {
            assertTrue(awaitItem().isEmpty()) // empty cache

            f.source.snapshot = ItemSnapshot(tasks = listOf(task("a"), task("b")), habits = listOf(habit("h")))
            f.sync.refresh()
            // One commit-time emission carrying every row, not one per upsert and not one per kind.
            assertEquals(setOf("a", "b", "h"), awaitItem().map { it.id }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
