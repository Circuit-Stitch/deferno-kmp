package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.common.asStateFlow
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.comment.CommentRepository
import com.circuitstitch.deferno.core.data.comment.CommentWriter
import com.circuitstitch.deferno.core.data.definition.DefinitionRepository
import com.circuitstitch.deferno.core.data.history.ItemHistoryRepository
import com.circuitstitch.deferno.core.data.occurrence.InertOccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.InertOccurrenceFactLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.CoroutineContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Instant

/** Which pane is foregrounded — the Item [Tree] (the primary Tasks pane) or a Task [Detail]. */
enum class TaskPane { Tree, Detail }

/**
 * The Tasks feature root (ADR-0049). The Tasks Destination is the nested, collapsible **Item tree**
 * ([tree]) spanning all four kinds; selecting a row's trailing `›` opens its [detail] alongside (a
 * co-resident slot, ADR-0007). The old flat list + one-level drill pane are **subsumed** — a node's
 * children are now seen inline by expanding the tree, so there is no separate tree slot anymore.
 *
 * A native View reads 1 or 2 panes by window size class: the tree, plus the detail when open. Navigation
 * is intent-driven — the tree emits an `ItemSelected`, which this root turns into detail activation, and
 * the detail's subtask-drill re-keys the same slot. [activePane] is the recency a single-pane View renders
 * and back dismisses. Cross-feature intents (add-to-plan) are re-emitted via this component's [Output].
 */
interface TasksComponent {
    val tree: ItemTreeComponent
    val detail: Value<ChildSlot<*, DetailChild>>

    /** The most-recently-foregrounded pane (see [TaskPane]); updated as the detail slot activates/dismisses. */
    val activePane: Value<TaskPane>

    /**
     * [detail], flattened to its nullable open [DetailChild] and mirrored as a [StateFlow] for
     * the SwiftUI Views to observe via SKIE (which bridges `StateFlow` but not Decompose's
     * [Value]/[ChildSlot]). The Compose/Android side keeps observing [detail] directly.
     */
    val activeDetail: StateFlow<DetailChild?>

    /**
     * Which detail is open (#383). Two arms, not four: a [[Task]] has a full read/write detail, and the
     * three recurring kinds share one read-only definition detail — they project identically for reading
     * and differ only in writes, which this slice does not have.
     *
     * Sealed rather than a widened `TaskDetailComponent` because that component's `taskId` is SwiftUI
     * view identity and its constructor fires five Task-scoped side effects; see
     * [DefinitionDetailComponent]'s KDoc for the full reasoning. This mirrors
     * `MainShellComponent.PlanChild`, already the same shape.
     */
    sealed interface DetailChild {

        /** Dismiss whichever detail is open — the one action the host shell needs kind-blind. */
        fun onCloseClicked()

        /**
         * The open Task detail, or `null` when a definition is open — and its twin below.
         *
         * Flat accessors beside the sealed arms because the two SwiftUI apps cannot `when` over a
         * Kotlin sealed hierarchy through the bridge, and their Views need exactly this question
         * answered. One pair of accessors here beats an `as?` repeated at every Swift and test site.
         */
        val asTask: TaskDetailComponent? get() = (this as? Task)?.component
        val asDefinition: DefinitionDetailComponent? get() = (this as? Definition)?.component

        class Task(val component: TaskDetailComponent) : DetailChild {
            override fun onCloseClicked() = component.onCloseClicked()
        }

        class Definition(val component: DefinitionDetailComponent) : DetailChild {
            override fun onCloseClicked() = component.onCloseClicked()
        }
    }

    sealed interface Output {
        data class AddToPlanRequested(val id: TaskId) : Output

        /** "Break this down" from the workspace detail (Deferno#525) — the host opens the Breakdown surface. */
        data class BreakdownRequested(val id: TaskId) : Output
    }
}

class DefaultTasksComponent(
    componentContext: ComponentContext,
    // The cross-kind Item read + device-local fold store the tree pane renders (ADR-0049, #226/#227).
    private val itemRepository: ItemRepository,
    private val foldStore: ItemFoldStore,
    // The Task read seam the Task arm of the detail slot observes/hydrates.
    private val taskRepository: TaskRepository,
    // The recurring-definition read seam the Definition arm observes/hydrates (#383), plus the two
    // occurrence stores its "today" reading is derived from and the reader's local today. All defaulted
    // so every existing caller and test constructs unchanged; the inert defaults render "not synced",
    // which is the honest reading for a host that has wired no stores.
    private val definitionRepository: DefinitionRepository = DefinitionRepository.NONE,
    private val occurrenceFacts: OccurrenceFactLocalStore = InertOccurrenceFactLocalStore,
    private val occurrenceCoverage: OccurrenceCoverageLocalStore = InertOccurrenceCoverageLocalStore,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
    private val output: (TasksComponent.Output) -> Unit = {},
    // The working-state write seam (#73), threaded down into the detail slot so the detail can issue
    // lifecycle Commands. Defaults to a no-op so existing shell/component tests build without it.
    private val workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
    // The non-Task status write seam (#299), threaded into the tree pane so its command menu can set a
    // recurring definition's state (Archive/Restore). Defaults to a no-op so existing tests build without it.
    private val definitionStateEditor: DefinitionStateEditor = DefinitionStateEditor.NONE,
    // The cross-kind move write seam (#228), threaded into the tree pane so its modal move mode can issue
    // Move Commands. Defaults to a no-op so existing shell/component tests build without it.
    private val moveEditor: MoveEditor = MoveEditor.NONE,
    // The device-local shake-to-undo App setting (#230), threaded into the tree pane so a shake gates on
    // it. Defaulted to an in-memory (on) preference so existing tests build without supplying it.
    private val shakeToUndoPreference: ShakeToUndoPreference = InMemoryShakeToUndoPreference(),
    // The detail's online-only attachments source + its "add subtask" create seam, threaded down into
    // the detail slot. Both default to no-ops so existing tests build without supplying them.
    private val taskDetailRepository: TaskDetailRepository = TaskDetailRepository.NONE,
    // The offline-first ACTIVITY feed (ADR-0043): comment thread + item history + the comment write seam
    // + the device-local user id, threaded down into the detail slot. Empty/no-op defaults.
    private val commentRepository: CommentRepository = CommentRepository.NONE,
    private val itemHistoryRepository: ItemHistoryRepository = ItemHistoryRepository.NONE,
    // This item's local activity-ledger slice (#260), threaded into the detail so the Trail can graft the
    // captured old->new values onto server `Updated` rows. Empty default for tests/callers without it.
    private val observeItemLedger: (String) -> Flow<List<ActivityEntry>> = { flowOf(emptyList()) },
    private val commentWriter: CommentWriter = CommentWriter.NONE,
    private val currentUserId: UserId? = null,
    private val createSubtask: suspend (TaskId, String) -> Unit = { _, _ -> },
    // The detail's editable-PROPERTIES write seams (DUE date + LABELS), threaded down into the detail
    // slot. Both default to no-ops so existing tests/callers build without supplying them.
    private val setDeadline: suspend (TaskId, Instant?) -> Unit = { _, _ -> },
    // The deadline clock-TIME seam (#348), threaded down into the detail slot for the combined date+time
    // picker (iOS). No-op default so existing tests/callers build without it.
    private val setDeadlineTime: suspend (TaskId, LocalTime?) -> Unit = { _, _ -> },
    // The soft target-date + priority write seams (#375), threaded beside the deadline pair.
    private val setTargetDate: suspend (TaskId, Instant?) -> Unit = { _, _ -> },
    private val setPriority: suspend (TaskId, Priority) -> Unit = { _, _ -> },
    private val setLabels: suspend (TaskId, List<String>) -> Unit = { _, _ -> },
    // The detail's destructive Delete seam (kebab → confirm), threaded down into the detail slot. Defaults
    // to a no-op so existing tests/callers build without supplying it. The Item tree's command menu (#231)
    // reuses it (and [workingStateEditor] / [createSubtask]) for a Task row's Delete / status / Add subtask.
    private val deleteTask: suspend (TaskId) -> Unit = { _ -> },
    // The tree pane's **kind-neutral** delete seam (#389) — the raw Item id, no kind, for a recurring
    // row's Delete (`DELETE items/{id}`, the whole Series chain). Threaded only into the tree: the detail
    // slot is Task-only, so it keeps [deleteTask]. No-op default like its Task twin.
    private val deleteDefinition: suspend (String) -> Unit = { _ -> },
    // The tree pane's per-row decorations (#231/#386) — the Task-only menu state (Pin/plan/status labels)
    // and the kind-neutral "in today" set — joined once by the shell and passed straight through.
    // Defaulted to the empty [ItemRowDecorations] so existing tests/callers build without it.
    private val decorations: Flow<ItemRowDecorations> = flowOf(ItemRowDecorations()),
    // The dependency-edge seam (#291), threaded into the tree pane (online-only, returns the verdict).
    private val blockedByEditor: BlockedByEditor = BlockedByEditor.NONE,
    private val setPinned: suspend (TaskId, Boolean) -> Unit = { _, _ -> },
    private val addToPlan: suspend (TaskId) -> Unit = { _ -> },
    private val removeFromPlan: suspend (TaskId) -> Unit = { _ -> },
    // The detail's on-device attachment seam (#211), threaded down into the detail slot. Defaults to the
    // empty NONE so existing tests/callers build without it.
    private val onDeviceAttachments: OnDeviceAttachments = OnDeviceAttachments.NONE,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : TasksComponent, ComponentContext by componentContext {

    // initialTask seeds the detail's first frame from a row the opener already had in memory (no DB read).
    // The tree opens by id only (its rows are the cross-kind Item projection, not full Tasks), so a
    // tree-opened detail has no seed and its title pops in one frame later; a subtask-drill still seeds.
    private data class DetailConfig(val ref: ItemRef, val initialTask: Task? = null)

    private val detailNavigation = SlotNavigation<DetailConfig>()

    private val scope = componentScope(coroutineContext)

    private val _activePane = MutableValue(TaskPane.Tree)
    override val activePane: Value<TaskPane> = _activePane

    override val tree: ItemTreeComponent =
        DefaultItemTreeComponent(
            componentContext = childContext(key = "tree"),
            itemRepository = itemRepository,
            foldStore = foldStore,
            output = ::onTreeOutput,
            moveEditor = moveEditor,
            shakeToUndoPreference = shakeToUndoPreference,
            decorations = decorations,
            workingStateEditor = workingStateEditor,
            definitionStateEditor = definitionStateEditor,
            blockedByEditor = blockedByEditor,
            setPinned = setPinned,
            createSubtask = createSubtask,
            deleteTask = deleteTask,
            deleteDefinition = deleteDefinition,
            addToPlan = addToPlan,
            removeFromPlan = removeFromPlan,
            coroutineContext = coroutineContext,
        )

    override val detail: Value<ChildSlot<*, TasksComponent.DetailChild>> =
        childSlot(
            source = detailNavigation,
            serializer = null,
            key = "detail",
            handleBackButton = false,
        ) { config, childContext ->
            // The kind decides the detail. `taskId` is non-null exactly when the ref is a Task, so the
            // two arms are total and the recurring id can never reach a TaskId-typed seam (ItemRef).
            val taskId = config.ref.taskId
                ?: return@childSlot TasksComponent.DetailChild.Definition(
                    DefaultDefinitionDetailComponent(
                        componentContext = childContext,
                        ref = config.ref,
                        definitionRepository = definitionRepository,
                        occurrenceFacts = occurrenceFacts,
                        occurrenceCoverage = occurrenceCoverage,
                        today = today,
                        output = ::onDefinitionDetailOutput,
                        coroutineContext = coroutineContext,
                    ),
                )
            TasksComponent.DetailChild.Task(
                DefaultTaskDetailComponent(
                    componentContext = childContext,
                    taskId = taskId,
                    taskRepository = taskRepository,
                    output = ::onDetailOutput,
                    workingStateEditor = workingStateEditor,
                    initialTask = config.initialTask,
                    detailRepository = taskDetailRepository,
                    commentRepository = commentRepository,
                    historyRepository = itemHistoryRepository,
                    observeItemLedger = observeItemLedger,
                    itemRepository = itemRepository,
                    commentWriter = commentWriter,
                    currentUserId = currentUserId,
                    createSubtask = createSubtask,
                    setDeadline = setDeadline,
                    setDeadlineTime = setDeadlineTime,
                    setTargetDate = setTargetDate,
                    setPriority = setPriority,
                    setLabels = setLabels,
                    delete = deleteTask,
                    onDeviceAttachments = onDeviceAttachments,
                    foldStore = foldStore,
                    coroutineContext = coroutineContext,
                ),
            )
        }

    override val activeDetail: StateFlow<TasksComponent.DetailChild?> =
        detail.asStateFlow(scope) { it.child?.instance }

    private fun onTreeOutput(output: ItemTreeComponent.Output) {
        when (output) {
            is ItemTreeComponent.Output.ItemSelected -> {
                detailNavigation.activate(DetailConfig(output.ref))
                _activePane.value = TaskPane.Detail
            }
        }
    }

    private fun onDefinitionDetailOutput(output: DefinitionDetailComponent.Output) {
        when (output) {
            DefinitionDetailComponent.Output.Closed -> {
                detailNavigation.dismiss()
                _activePane.value = TaskPane.Tree
            }
        }
    }

    private fun onDetailOutput(output: TaskDetailComponent.Output) {
        when (output) {
            TaskDetailComponent.Output.Closed -> {
                detailNavigation.dismiss()
                _activePane.value = TaskPane.Tree
            }
            // Tapping a subtask re-keys the detail slot to that child (inline drill-in). Seed from the
            // detail's in-memory subtask outline (the visible row it just tapped) so the re-keyed title
            // shows now.
            is TaskDetailComponent.Output.SubtaskSelected -> {
                // A subtask is always a Task — the outline is typed `SubtaskRow(task: Task)` — so this
                // re-key stays on the Task arm by construction.
                val seed = (detail.value.child?.instance as? TasksComponent.DetailChild.Task)
                    ?.component?.state?.value
                    ?.subtaskRows?.firstOrNull { it.task.id == output.id }?.task
                detailNavigation.activate(DetailConfig(ItemRef(output.id.value, ItemKind.Task), seed))
                _activePane.value = TaskPane.Detail
            }
            is TaskDetailComponent.Output.AddToPlanRequested ->
                this.output(TasksComponent.Output.AddToPlanRequested(output.id))
            is TaskDetailComponent.Output.BreakdownRequested ->
                this.output(TasksComponent.Output.BreakdownRequested(output.id))
        }
    }
}
