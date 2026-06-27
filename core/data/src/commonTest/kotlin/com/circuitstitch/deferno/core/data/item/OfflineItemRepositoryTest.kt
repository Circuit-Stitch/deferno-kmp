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
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unified cross-kind read of [OfflineItemRepository] (ADR-0034, #226) — the read half of the Item
 * store the Tasks [Item tree] (#227) renders as one forest. Proves [observeItems] merges all four
 * per-kind caches into one list, projects each kind's common fields (incl. the de-emphasis [isTerminal]
 * signal and the Task-only subtree counts), re-emits when any kind changes, and that [refresh] delegates
 * the cross-kind `/items` cold sync to [ItemSync]. Runs on the ADR-0006 JVM-fast path against the fakes.
 */
class OfflineItemRepositoryTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun task(
        id: String,
        state: WorkingState = WorkingState.Open,
        parentId: String? = null,
        sequence: Long? = null,
        descendantDone: Long? = null,
        descendantTotal: Long? = null,
        blocked: Boolean = false,
        isBlocker: Boolean = false,
    ) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = "task-$id",
        workingState = state,
        parentId = parentId?.let(::TaskId),
        sequence = sequence,
        dateCreated = created,
        hydration = HydrationState.Full,
        descendantDone = descendantDone,
        descendantTotal = descendantTotal,
        blocked = blocked,
        isBlocker = isBlocker,
    )

    private fun habit(
        id: String,
        state: DefinitionState = DefinitionState.Active,
        parentId: String? = null,
        sequence: Long? = null,
        blocked: Boolean = false,
        isBlocker: Boolean = false,
    ) = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = "habit-$id",
        definitionState = state,
        parentId = parentId?.let(::TaskId),
        sequence = sequence,
        dateCreated = created,
        hydration = HydrationState.Full,
        blocked = blocked,
        isBlocker = isBlocker,
    )

    private fun chore(id: String, sequence: Long? = null) = Chore(
        id = ChoreId(id),
        orgSlug = "u-e4h2qk",
        title = "chore-$id",
        definitionState = DefinitionState.Active,
        sequence = sequence,
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private fun event(id: String, sequence: Long? = null) = Event(
        id = EventId(id),
        orgSlug = "u-e4h2qk",
        title = "event-$id",
        definitionState = DefinitionState.Active,
        sequence = sequence,
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
        val repository = OfflineItemRepository(tasks, habits, chores, events, sync)
    }

    @Test
    fun observeItemsMergesAllFourKindsIntoOneList() = runTest {
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h"))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c"))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e"))),
        )

        f.repository.observeItems().test {
            val items = awaitItem()
            assertEquals(setOf("t" to ItemKind.Task, "h" to ItemKind.Habit, "c" to ItemKind.Chore, "e" to ItemKind.Event), items.map { it.id to it.kind }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun projectsATaskWithItsParentTerminalStateAndSubtreeCounts() = runTest {
        val f = Fixture(
            tasks = FakeTaskLocalStore(
                mapOf(TaskId("child") to task("child", WorkingState.Done, parentId = "root", sequence = 7, descendantDone = 2, descendantTotal = 5)),
            ),
        )

        f.repository.observeItems().test {
            val item = awaitItem().single()
            assertEquals(Item(id = "child", kind = ItemKind.Task, title = "task-child", parentId = "root", sequence = 7, isTerminal = true, descendantDone = 2, descendantTotal = 5), item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun projectsAnActiveRecurringDefinitionAsNonTerminalWithNoSubtreeCounts() = runTest {
        val f = Fixture(habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", parentId = "root", sequence = 3))))

        f.repository.observeItems().test {
            val item = awaitItem().single()
            assertEquals("root", item.parentId)
            assertEquals(false, item.isTerminal)
            assertNull(item.descendantDone)
            assertNull(item.descendantTotal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun projectsServerDerivedBlockedAndBlockerFlagsAcrossKinds() = runTest {
        // #289: blocked/isBlocker are server-derived per item; the projection forwards them unchanged
        // for a Task and for a recurring kind (a habit can inherit `blocked` from a blocked ancestor).
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t", blocked = true, isBlocker = false))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", blocked = false, isBlocker = true))),
        )

        f.repository.observeItems().test {
            val byId = awaitItem().associateBy { it.id }
            assertEquals(true, byId.getValue("t").blocked)
            assertEquals(false, byId.getValue("t").isBlocker)
            assertEquals(false, byId.getValue("h").blocked)
            assertEquals(true, byId.getValue("h").isBlocker)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deEmphasizesAnArchivedRecurringDefinition() = runTest {
        val f = Fixture(habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", state = DefinitionState.Archived))))

        f.repository.observeItems().test {
            assertTrue(awaitItem().single().isTerminal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun carriesDefinitionStateOnRecurringRowsAndNullOnTasks() = runTest {
        // #299: the recurring "light switch" is carried through the projection (for the tree's command menu),
        // populated per kind; a Task has no definition state, so its row carries null (its lifecycle is
        // WorkingState). isTerminal is still derived (Archived → terminal) — this is the FULL state alongside it.
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", state = DefinitionState.InReview))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c"))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e"))),
        )

        f.repository.observeItems().test {
            val byId = awaitItem().associateBy { it.id }
            assertNull(byId.getValue("t").definitionState, "a Task carries no definition state")
            assertEquals(DefinitionState.InReview, byId.getValue("h").definitionState)
            assertEquals(DefinitionState.Active, byId.getValue("c").definitionState)
            assertEquals(DefinitionState.Active, byId.getValue("e").definitionState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun reEmitsWhenAnyKindChanges() = runTest {
        val f = Fixture()

        f.repository.observeItems().test {
            assertTrue(awaitItem().isEmpty())

            f.habits.upsert(habit("h"))
            assertEquals(listOf("h" to ItemKind.Habit), awaitItem().map { it.id to it.kind })

            f.tasks.upsert(task("t"))
            assertEquals(setOf("h", "t"), awaitItem().map { it.id }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshDelegatesTheCrossKindColdSyncToItemSync() = runTest {
        val f = Fixture()
        f.source.snapshot = ItemSnapshot(
            tasks = listOf(task("t")),
            habits = listOf(habit("h")),
            chores = listOf(chore("c")),
            events = listOf(event("e")),
        )
        assertTrue(f.repository.observeItems().first().isEmpty()) // empty caches before the pull

        f.repository.refresh()

        // refresh() commits all four per-kind reconciles before returning, so the next read is the
        // fully-merged set (not one of combine's per-commit intermediate emissions).
        assertEquals(setOf("t", "h", "c", "e"), f.repository.observeItems().first().map { it.id }.toSet())
    }
}
