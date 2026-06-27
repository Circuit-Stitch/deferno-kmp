package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Item-tree pane component (ADR-0034, #227): it flattens the cross-kind Item forest into the
 * observable [ItemTreeState], persists fold toggles to the device-local store (re-flattening live), opens
 * detail only for Task rows, and delegates refresh. Run against the fakes on the ADR-0006 JVM-fast path.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drive the WhileSubscribed state flow
class ItemTreeComponentTest {

    private fun TestScope.component(
        items: FakeItemRepository,
        foldStore: InMemoryItemFoldStore = InMemoryItemFoldStore(),
        output: (ItemTreeComponent.Output) -> Unit = {},
        moveEditor: MoveEditor = MoveEditor.NONE,
        shakeToUndoPreference: ShakeToUndoPreference = InMemoryShakeToUndoPreference(),
        trackEvent: (String) -> Unit = {},
        menuStates: Flow<Map<String, TaskMenuState>> = flowOf(emptyMap()),
        workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
        definitionStateEditor: DefinitionStateEditor = DefinitionStateEditor.NONE,
        setPinned: suspend (TaskId, Boolean) -> Unit = { _, _ -> },
        createSubtask: suspend (TaskId, String) -> Unit = { _, _ -> },
        deleteTask: suspend (TaskId) -> Unit = { _ -> },
        addToPlan: suspend (TaskId) -> Unit = { _ -> },
        removeFromPlan: suspend (TaskId) -> Unit = { _ -> },
    ) = DefaultItemTreeComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        itemRepository = items,
        foldStore = foldStore,
        output = output,
        moveEditor = moveEditor,
        shakeToUndoPreference = shakeToUndoPreference,
        trackEvent = trackEvent,
        menuStates = menuStates,
        workingStateEditor = workingStateEditor,
        definitionStateEditor = definitionStateEditor,
        setPinned = setPinned,
        createSubtask = createSubtask,
        deleteTask = deleteTask,
        addToPlan = addToPlan,
        removeFromPlan = removeFromPlan,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    private fun rootAndChild() = FakeItemRepository(
        listOf(
            Item(id = "root", kind = ItemKind.Task, title = "root", sequence = 0),
            Item(id = "child", kind = ItemKind.Task, title = "child", parentId = "root", sequence = 1),
        ),
    )

    /** root → a, b (two reorderable siblings) for the move-mode tests. */
    private fun siblings() = FakeItemRepository(
        listOf(
            Item(id = "root", kind = ItemKind.Task, title = "root", sequence = 0),
            Item(id = "a", kind = ItemKind.Task, title = "a", parentId = "root", sequence = 0),
            Item(id = "b", kind = ItemKind.Task, title = "b", parentId = "root", sequence = 1),
        ),
    )

    /** root → a, plus a blocked sibling `bad` — for the readiness-axis tests (#290). */
    private fun withBlocked() = FakeItemRepository(
        listOf(
            Item(id = "root", kind = ItemKind.Task, title = "root", sequence = 0),
            Item(id = "a", kind = ItemKind.Task, title = "a", parentId = "root", sequence = 0),
            Item(id = "bad", kind = ItemKind.Task, title = "bad", parentId = "root", sequence = 1, blocked = true),
        ),
    )

    @Test
    fun stateFlattensTheCrossKindForest() = runTest {
        val c = component(rootAndChild())
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("root", "child"), c.state.value.rows.map { it.item.id })
        assertTrue(c.state.value.rows.first().isExpanded)
    }

    @Test
    fun togglingAParentPersistsTheFoldAndReFlattensLive() = runTest {
        val foldStore = InMemoryItemFoldStore()
        val c = component(rootAndChild(), foldStore)
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        c.onToggleExpand("root", currentlyExpanded = true) // collapse the root
        advanceUntilIdle()

        assertEquals(listOf("root"), c.state.value.rows.map { it.item.id }, "child hidden under the collapsed root")
        assertFalse(c.state.value.rows.single().isExpanded)
        assertEquals(false, foldStore.overrides.value["root"], "the collapse is persisted device-locally")
    }

    @Test
    fun seedsItsFoldFromThePersistedOverrides() = runTest {
        // The root would expand by default (depth 0), but a persisted collapse override wins.
        val c = component(rootAndChild(), InMemoryItemFoldStore(initial = mapOf("root" to false)))
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("root"), c.state.value.rows.map { it.item.id })
        assertFalse(c.state.value.rows.single().isExpanded)
    }

    // --- readiness axis (#290): ready-only by default, "show blocked" reveals ---

    @Test
    fun readyOnlyIsTheRestingDefaultAndPrunesBlockedItems() = runTest {
        val c = component(withBlocked())
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        assertFalse(c.state.value.showBlocked, "ready-only is the resting default (#290)")
        assertEquals(listOf("root", "a"), c.state.value.rows.map { it.item.id }, "the blocked item is pruned by default")
    }

    @Test
    fun showingBlockedRevealsThemAndReFlattensLive() = runTest {
        val c = component(withBlocked())
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        c.onSetShowBlocked(true)
        advanceUntilIdle()

        assertTrue(c.state.value.showBlocked)
        assertEquals(setOf("root", "a", "bad"), c.state.value.rows.map { it.item.id }.toSet(), "blocked items revealed live")
    }

    @Test
    fun openingATaskRowEmitsItsDetailIntent() = runTest {
        val outputs = mutableListOf<ItemTreeComponent.Output>()
        val c = component(rootAndChild(), output = outputs::add)

        c.onOpenDetail("root", ItemKind.Task)

        assertEquals(listOf<ItemTreeComponent.Output>(ItemTreeComponent.Output.ItemSelected(TaskId("root"))), outputs)
    }

    @Test
    fun openingANonTaskRowEmitsNothing() = runTest {
        val outputs = mutableListOf<ItemTreeComponent.Output>()
        val c = component(rootAndChild(), output = outputs::add)

        c.onOpenDetail("some-habit", ItemKind.Habit)

        assertTrue(outputs.isEmpty())
    }

    @Test
    fun refreshDelegatesToTheRepository() = runTest {
        val items = rootAndChild()
        val c = component(items)

        c.onRefresh()
        advanceUntilIdle()

        assertEquals(1, items.refreshCount)
    }

    // --- modal move mode (ADR-0034 decision 6, #228) ---

    @Test
    fun enteringMoveModeLiftsTheItemAndGreysTheIllegalDirections() = runTest {
        val c = component(siblings())
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        c.onEnterMoveMode("a") // a is the first child: up + indent are illegal
        advanceUntilIdle()

        val move = c.state.value.moveMode!!
        assertEquals("a", move.liftedId)
        assertFalse(move.canMoveUp, "first child can't move up")
        assertTrue(move.canMoveDown)
        assertFalse(move.canIndent, "first child has no preceding sibling")
        assertTrue(move.canOutdent)
    }

    @Test
    fun aLegalDirectionDispatchesTheMoveWithItsComputedTarget() = runTest {
        val moves = mutableListOf<Triple<String, String?, Int>>()
        val c = component(siblings(), moveEditor = { id, parent, pos -> moves += Triple(id, parent, pos) })

        c.onEnterMoveMode("a")
        c.onMoveDown() // a → after b, in the group excluding a → position 1 under root
        advanceUntilIdle()

        assertEquals(listOf(Triple<String, String?, Int>("a", "root", 1)), moves)
    }

    @Test
    fun anIllegalDirectionDispatchesNothing() = runTest {
        val moves = mutableListOf<Triple<String, String?, Int>>()
        val c = component(siblings(), moveEditor = { id, parent, pos -> moves += Triple(id, parent, pos) })

        c.onEnterMoveMode("a")
        c.onMoveUp() // a is the first child — illegal, greyed
        advanceUntilIdle()

        assertTrue(moves.isEmpty(), "a greyed direction must issue no Move")
    }

    @Test
    fun doneExitsMoveMode() = runTest {
        val c = component(siblings())
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        c.onEnterMoveMode("a")
        advanceUntilIdle()
        assertEquals("a", c.state.value.moveMode?.liftedId)

        c.onExitMoveMode()
        advanceUntilIdle()
        assertEquals(null, c.state.value.moveMode)
    }

    @Test
    fun aMoveDispatchedWithNoEditorIsASafeNoOp() = runTest {
        // The default NONE editor: the move-mode methods still run (no crash), they just don't write.
        val c = component(siblings())

        c.onEnterMoveMode("a")
        c.onMoveDown()
        advanceUntilIdle() // no exception, nothing to assert beyond "did not throw"
    }

    // --- undo: lastUndoable + snackbar + shake (ADR-0034 decision 8, #230) ---

    @Test
    fun aStructuralMoveRecordsAnUndoableAndUndoRevertsViaTheSameEditor() = runTest {
        val moves = mutableListOf<Triple<String, String?, Int>>()
        val c = component(siblings(), moveEditor = { id, parent, pos -> moves += Triple(id, parent, pos) })
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        c.onEnterMoveMode("b")
        c.onIndent() // b nests under a → a parent change → structural
        advanceUntilIdle()

        assertEquals(Triple<String, String?, Int>("b", "a", 0), moves.single(), "the forward indent")
        val undo = c.state.value.lastMove!!
        assertTrue(undo.structural, "indent is structural → the 'Moved · Undo' snackbar")
        assertEquals("indent", undo.operation)

        c.undoLastMove()
        advanceUntilIdle()
        assertEquals(
            Triple<String, String?, Int>("b", "root", 1),
            moves.last(),
            "the inverse moves b back to its pre-move slot, via the same editor",
        )
        assertEquals(null, c.state.value.lastMove, "single-level: the entry is consumed after one undo")
    }

    @Test
    fun aPlainReorderIsUndoableButNotStructural() = runTest {
        val c = component(siblings(), moveEditor = { _, _, _ -> })
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        c.onEnterMoveMode("a")
        c.onMoveDown() // same parent → a plain reorder
        advanceUntilIdle()

        val undo = c.state.value.lastMove!!
        assertFalse(undo.structural, "a same-level reorder records an undoable but shows no snackbar")
        assertEquals("reorder", undo.operation)
    }

    @Test
    fun shakeWithAnUndoableMoveAsksToConfirm() = runTest {
        val c = component(siblings(), moveEditor = { _, _, _ -> })
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()
        c.onEnterMoveMode("b"); c.onIndent(); advanceUntilIdle()

        assertEquals(ShakeOutcome.Confirm("indent"), c.onShake())
    }

    @Test
    fun shakeWithNothingToUndoEmitsATrackingEvent() = runTest {
        val events = mutableListOf<String>()
        val c = component(siblings(), trackEvent = events::add)
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        assertEquals(ShakeOutcome.Nothing, c.onShake(), "no Move to undo → nothing to confirm")
        assertEquals(listOf("shake_undo_no_target"), events, "an unsupported-context shake emits a tracking event")
    }

    @Test
    fun shakeIsASilentNoOpWhenTheToggleIsOff() = runTest {
        val events = mutableListOf<String>()
        val c = component(
            siblings(),
            moveEditor = { _, _, _ -> },
            shakeToUndoPreference = InMemoryShakeToUndoPreference(initial = false),
            trackEvent = events::add,
        )
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()
        c.onEnterMoveMode("b"); c.onIndent(); advanceUntilIdle()

        assertEquals(ShakeOutcome.Nothing, c.onShake(), "toggle off → a shake does nothing")
        assertTrue(events.isEmpty(), "off is not an 'unsupported context' — it emits no tracking event")
        assertTrue(c.state.value.lastMove != null, "the snackbar + menu undo paths remain when shake is off")
    }

    // --- kind-aware command menu (ADR-0034 decision 7, #231) ---

    @Test
    fun menuStatesAreSurfacedOnTheStateForTheView() = runTest {
        val states = mapOf("root" to TaskMenuState(WorkingState.InProgress, pinned = true, inPlan = false))
        val c = component(rootAndChild(), menuStates = flowOf(states))
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        assertEquals(states, c.state.value.menuStates, "the per-row Task menu state reaches the View")
    }

    @Test
    fun addSubtaskCreatesATaskChildUnderTheRowAndTrimsTheTitle() = runTest {
        val created = mutableListOf<Pair<TaskId, String>>()
        val c = component(rootAndChild(), createSubtask = { parent, title -> created += parent to title })

        c.onAddSubtask("root", "  buy milk  ")
        advanceUntilIdle()

        assertEquals(listOf(TaskId("root") to "buy milk"), created, "the child is created under the row, trimmed")
    }

    @Test
    fun addSubtaskWithABlankTitleIsANoOp() = runTest {
        val created = mutableListOf<Pair<TaskId, String>>()
        val c = component(rootAndChild(), createSubtask = { parent, title -> created += parent to title })

        c.onAddSubtask("root", "   ")
        advanceUntilIdle()

        assertTrue(created.isEmpty(), "a blank subtask title writes nothing")
    }

    @Test
    fun setPinnedDispatchesThePinWriteWithTheTargetValue() = runTest {
        val pins = mutableListOf<Pair<TaskId, Boolean>>()
        val c = component(rootAndChild(), setPinned = { id, pinned -> pins += id to pinned })

        c.onSetPinned("root", pinned = true)
        advanceUntilIdle()

        assertEquals(listOf(TaskId("root") to true), pins)
    }

    @Test
    fun setInPlanRoutesToAddOrRemoveByTheTargetValue() = runTest {
        val added = mutableListOf<TaskId>()
        val removed = mutableListOf<TaskId>()
        val c = component(
            rootAndChild(),
            addToPlan = { added += it },
            removeFromPlan = { removed += it },
        )

        c.onSetInPlan("root", inPlan = true)
        c.onSetInPlan("child", inPlan = false)
        advanceUntilIdle()

        assertEquals(listOf(TaskId("root")), added, "in-plan=true adds to today's plan")
        assertEquals(listOf(TaskId("child")), removed, "in-plan=false removes from today's plan")
    }

    @Test
    fun setWorkingStateDispatchesTheStatusWrite() = runTest {
        val sets = mutableListOf<Pair<TaskId, WorkingState>>()
        val c = component(
            rootAndChild(),
            workingStateEditor = WorkingStateEditor { id, target, _ -> sets += id to target },
        )

        c.onSetWorkingState("root", WorkingState.Done)
        advanceUntilIdle()

        assertEquals(listOf(TaskId("root") to WorkingState.Done), sets)
    }

    @Test
    fun setDefinitionStateResolvesTheRowsKindAndDispatchesTheWrite() = runTest {
        // #299: the tree row is the cross-kind Item projection, so the component resolves the row's kind
        // from its current state (the writer needs it to route the per-kind PATCH) and forwards id/kind/target.
        val sets = mutableListOf<Triple<String, ItemKind, DefinitionState>>()
        val items = FakeItemRepository(
            listOf(
                Item(id = "root", kind = ItemKind.Task, title = "root", sequence = 0),
                Item(id = "hab", kind = ItemKind.Habit, title = "habit", sequence = 1, definitionState = DefinitionState.Active),
            ),
        )
        val c = component(
            items,
            definitionStateEditor = { id, kind, target -> sets += Triple(id, kind, target) },
        )
        backgroundScope.launch { c.state.collect {} } // populate state.rows so the kind resolves
        advanceUntilIdle()

        c.onSetDefinitionState("hab", DefinitionState.Archived)
        advanceUntilIdle()

        assertEquals(listOf(Triple("hab", ItemKind.Habit, DefinitionState.Archived)), sets)
    }

    @Test
    fun setDefinitionStateForAnUncachedRowIsANoOp() = runTest {
        // A row not in the current tree can't be routed (no kind), so it writes nothing rather than guess.
        val sets = mutableListOf<Triple<String, ItemKind, DefinitionState>>()
        val c = component(
            rootAndChild(),
            definitionStateEditor = { id, kind, target -> sets += Triple(id, kind, target) },
        )
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        c.onSetDefinitionState("ghost", DefinitionState.Archived)
        advanceUntilIdle()

        assertTrue(sets.isEmpty(), "an uncached row resolves no kind → no write")
    }

    @Test
    fun deleteDispatchesTheDestructiveWrite() = runTest {
        val deleted = mutableListOf<TaskId>()
        val c = component(rootAndChild(), deleteTask = { deleted += it })

        c.onDelete("root")
        advanceUntilIdle()

        assertEquals(listOf(TaskId("root")), deleted)
    }
}
