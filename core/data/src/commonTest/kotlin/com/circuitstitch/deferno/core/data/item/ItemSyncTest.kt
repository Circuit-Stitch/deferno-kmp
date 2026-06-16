package com.circuitstitch.deferno.core.data.item

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
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
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cross-kind reconcile of [ItemSync] (ADR-0034, #226) — the heart of the `/tasks` -> `/items`
 * migration, run against the in-memory fakes on the ADR-0006 JVM-fast path. Proves the cold `/items`
 * snapshot is reconciled into all four per-kind stores (upsert + per-kind orphan-purge), that the
 * server-windowed snapshot honours the done-visibility window with no client-side window math, that
 * offline creates are protected from the purge, and that an unavailable pull leaves every cache intact.
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
        val tasks: FakeTaskLocalStore = FakeTaskLocalStore(),
        val habits: FakeHabitLocalStore = FakeHabitLocalStore(),
        val chores: FakeChoreLocalStore = FakeChoreLocalStore(),
        val events: FakeEventLocalStore = FakeEventLocalStore(),
        val source: FakeItemSnapshotSource = FakeItemSnapshotSource(),
        val pending: FakePendingCreateStore = FakePendingCreateStore(),
    ) {
        val sync = ItemSync(tasks, habits, chores, events, source, pending)
    }

    // --- upsert: every kind into its own store ---

    @Test
    fun refreshUpsertsEveryKindIntoItsOwnStore() = runTest {
        val f = Fixture()
        f.source.snapshot = ItemSnapshot(
            tasks = listOf(task("t")),
            habits = listOf(habit("h")),
            chores = listOf(chore("c")),
            events = listOf(event("e")),
        )

        f.sync.refresh()

        assertEquals(setOf(TaskId("t")), f.tasks.allIds())
        assertEquals(setOf(HabitId("h")), f.habits.allIds())
        assertEquals(setOf(ChoreId("c")), f.chores.allIds())
        assertEquals(setOf(EventId("e")), f.events.allIds())
    }

    // --- per-kind orphan purge ---

    @Test
    fun refreshPurgesPerKindTheRowsAbsentFromTheSnapshot() = runTest {
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("keep") to task("keep"), TaskId("gone") to task("gone"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("keep") to habit("keep"), HabitId("gone") to habit("gone"))),
        )
        f.source.snapshot = ItemSnapshot(tasks = listOf(task("keep")), habits = listOf(habit("keep")))

        f.sync.refresh()

        assertEquals(setOf(TaskId("keep")), f.tasks.allIds())
        assertEquals(setOf(HabitId("keep")), f.habits.allIds())
    }

    // --- AC3: the done-visibility window is honoured by the server-windowed snapshot (no client math) ---

    @Test
    fun aDoneTaskAgedOutOfTheWindowIsAbsentAfterRefreshWhileARecentlyDoneOneAndRecurringKindsRemain() = runTest {
        // Local cache holds a long-aged Done task, a recently-Done task, and a recurring habit.
        val f = Fixture(
            tasks = FakeTaskLocalStore(
                mapOf(
                    TaskId("old-done") to task("old-done", WorkingState.Done),
                    TaskId("recent-done") to task("recent-done", WorkingState.Done),
                ),
            ),
            habits = FakeHabitLocalStore(mapOf(HabitId("daily") to habit("daily"))),
        )
        // The server applies the window: the long-aged Done task falls out of the snapshot; the
        // recently-Done one and the (never-aging) recurring habit stay. No client-side window math.
        f.source.snapshot = ItemSnapshot(
            tasks = listOf(task("recent-done", WorkingState.Done)),
            habits = listOf(habit("daily")),
        )

        f.sync.refresh()

        assertFalse(f.tasks.all.containsKey(TaskId("old-done"))) // aged out -> purged
        assertTrue(f.tasks.all.containsKey(TaskId("recent-done"))) // within window -> kept
        assertTrue(f.habits.all.containsKey(HabitId("daily"))) // recurring -> never ages out
    }

    // --- offline creates are protected from the purge (#185), per kind ---

    @Test
    fun refreshDoesNotPurgeAnOfflineCreatedRowStillAwaitingReplay() = runTest {
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("offline-task") to task("offline-task"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("offline-habit") to habit("offline-habit"))),
        )
        f.pending.add("offline-task", ItemKind.Task)
        f.pending.add("offline-habit", ItemKind.Habit)
        f.source.snapshot = ItemSnapshot() // empty server snapshot: neither has replayed yet

        f.sync.refresh()

        assertEquals(setOf(TaskId("offline-task")), f.tasks.allIds())
        assertEquals(setOf(HabitId("offline-habit")), f.habits.allIds())
    }

    // --- offline-first: an unavailable pull is a no-op across every cache ---

    @Test
    fun anUnavailablePullLeavesEveryCacheIntact() = runTest {
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h"))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c"))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e"))),
        )
        f.source.failNext = true // couldn't reach the server

        f.sync.refresh()

        assertEquals(setOf(TaskId("t")), f.tasks.allIds())
        assertEquals(setOf(HabitId("h")), f.habits.allIds())
        assertEquals(setOf(ChoreId("c")), f.chores.allIds())
        assertEquals(setOf(EventId("e")), f.events.allIds())
    }

    @Test
    fun aGenuinelyEmptyAvailableSnapshotPurgesEveryNonPendingCache() = runTest {
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h"))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c"))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e"))),
        )
        f.source.snapshot = ItemSnapshot() // reachable, genuinely-empty server

        f.sync.refresh()

        assertTrue(f.tasks.allIds().isEmpty())
        assertTrue(f.habits.allIds().isEmpty())
        assertTrue(f.chores.allIds().isEmpty())
        assertTrue(f.events.allIds().isEmpty())
    }

    // --- a Full /items row replaces wholesale; a snapshot tombstone is kept ---

    @Test
    fun aFullSnapshotRowReplacesTheCachedRow() = runTest {
        val f = Fixture(tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t", WorkingState.Open))))
        f.source.snapshot = ItemSnapshot(tasks = listOf(task("t", WorkingState.Done).copy(title = "renamed")))

        f.sync.refresh()

        val row = f.tasks.all.getValue(TaskId("t"))
        assertEquals(WorkingState.Done, row.workingState)
        assertEquals("renamed", row.title)
    }

    @Test
    fun aSnapshotTombstoneIsKeptAsADeletedRowExcludedFromObserve() = runTest {
        val f = Fixture()
        f.source.snapshot = ItemSnapshot(
            tasks = listOf(task("a"), task("gone", deletedAt = Instant.parse("2026-06-01T00:00:00Z"))),
        )

        f.sync.refresh()

        assertTrue(f.tasks.all.getValue(TaskId("gone")).isDeleted)
        f.tasks.observeActive().test {
            assertEquals(listOf(TaskId("a")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- the reconcile commits as one transaction per kind (single observe emission) ---

    @Test
    fun aReconcileReEmitsTheTaskListOnceAtCommit() = runTest {
        val f = Fixture()
        f.tasks.observeActive().test {
            assertTrue(awaitItem().isEmpty()) // empty cache

            f.source.snapshot = ItemSnapshot(tasks = listOf(task("a"), task("b")))
            f.sync.refresh()
            // One commit-time emission carrying both rows, not one per upsert.
            assertEquals(setOf(TaskId("a"), TaskId("b")), awaitItem().map { it.id }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
