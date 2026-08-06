package com.circuitstitch.deferno.macos

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.feature.tasks.DefaultDefinitionDetailComponent
import com.circuitstitch.deferno.feature.tasks.DefaultTaskDetailStackComponent
import com.circuitstitch.deferno.feature.tasks.DefinitionDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailStackComponent
import com.circuitstitch.deferno.macos.bridge.itemRefFromToken
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * The Task arm of a detached detail window (#196, ADR-0033): a navigable push/pop **stack**, because a
 * Task detail can drill into a subtask and back.
 *
 * Split out of [ItemDetailWindowRoot] when the window learned the other three kinds (#383). Its two
 * `StateFlow`s stay non-null here rather than becoming nullable members of the root: SwiftUI observes
 * them through `StateFlowObserver`, and a nullable flow would push "is there a stack at all?" into every
 * observation site instead of being asked once.
 */
class TaskDetailWindowStack internal constructor(private val component: TaskDetailStackComponent) {
    /** The window's foreground detail page + whether a level can be popped (the Back control's gate). */
    val activeDetail: StateFlow<TaskDetailComponent> = component.activeDetail
    val canGoBack: StateFlow<Boolean> = component.canGoBack

    /** Pop one drilled level (the Back control); false at the root, where the window's chrome closes it. */
    fun onBack(): Boolean = component.onBack()
}

/**
 * The Swift-facing handle for one **detached item detail window** (#196, ADR-0033). It owns the window's
 * own Essenty [LifecycleRegistry] (resumed at open, [destroy]ed when SwiftUI tears the scene down — the
 * per-window lifecycle, mirroring [DefernoRoot]).
 *
 * It is built from the **live** [com.circuitstitch.deferno.shell.AccountSession] the main shell already
 * holds (via [openItemDetailWindow]), not a fresh `AccountComponent` — so the window and the main shell
 * share one SQLite driver and a write in either re-emits into the other's query Flow (the cross-window
 * live-sync AC; ADR-0033 deviation from the issue's per-call `createAccountComponent`).
 *
 * **Exactly one of [taskStack] / [definitionDetail] is non-null**, decided by [ref]'s kind — the same
 * two-armed split `TasksComponent.DetailChild` makes for the inline pane, and for the same reason: the
 * Task detail is a read/write surface with a drill stack, and the three recurring kinds share one
 * read-only definition detail with neither (#383). Swift asks which it got with `if let`; there is no
 * sealed type to take apart because this class is the macOS app's own, not shared code.
 */
class ItemDetailWindowRoot internal constructor(
    private val lifecycle: LifecycleRegistry,
    /** The kind-carrying address the window opened on — used for its title fetch and its scene dedupe. */
    val ref: ItemRef,
    val taskStack: TaskDetailWindowStack? = null,
    val definitionDetail: DefinitionDetailComponent? = null,
) {
    /** Tear down the window's component tree when its SwiftUI scene goes away (no leaks across open/close). */
    fun destroy() {
        lifecycle.destroy()
    }
}

/**
 * Open a detached detail window on the item [token] names, over the **active** account session (#196).
 * Returns `null` when signed out (the Auth shell — [RootComponent.activeAccountSession] is null) or when
 * the token names nothing addressable, so the Swift opener simply does nothing in those cases (detail
 * windows are unavailable when signed out).
 *
 * **[token] carries the KIND** (`"Habit:<uuid>"`, see `Bridge.itemRefToken`), and that is the whole point
 * of this signature. It used to be a bare id which this function re-wrapped as a `TaskId` — so a Habit
 * double-clicked into a window went down a Task-typed path: `taskRepository.hydrate` → `GET
 * /tasks/{habitId}` → a 404 the outbox posture reads as success, and a permanently empty "Task not found"
 * pane. `ItemRef` is what makes that unrepresentable (`ItemRef.taskId` is null for the recurring kinds),
 * so the kind has to survive the one boundary that can only carry a string: SwiftUI's
 * `WindowGroup(for: String.self)` scene value.
 *
 * [host] rather than a bare [RootComponent] because a definition's detail needs the reader's **today**,
 * and the app's own composition root is the one place that is already injected (`DefernoRoot.today`) — a
 * clock read here would be a second, disagreeing "today" on the same screen as the shell's.
 *
 * `createSubtask` is left at its no-op default — add-subtask in a window is #197.
 */
fun openItemDetailWindow(host: DefernoRoot, token: String): ItemDetailWindowRoot? {
    val session = host.root.activeAccountSession ?: return null
    val ref = itemRefFromToken(token) ?: return null

    val lifecycle = LifecycleRegistry()
    // `taskId` is non-null exactly when the ref is a Task, so the two arms are total and a recurring id
    // can never reach a TaskId-typed seam — the same construction `DefaultTasksComponent.detail` uses.
    val taskId = ref.taskId
    val root = if (taskId == null) {
        // `output` is left at its no-op default: this window has nothing to dismiss *to*. Its own chrome
        // closes it, so the read-only detail renders with its Back control hidden — the same thing the
        // Task arm does at stack depth 1.
        ItemDetailWindowRoot(
            lifecycle = lifecycle,
            ref = ref,
            definitionDetail = DefaultDefinitionDetailComponent(
                componentContext = DefaultComponentContext(lifecycle),
                ref = ref,
                definitionRepository = session.definitionRepository,
                occurrenceFacts = session.occurrenceFactLocalStore,
                occurrenceCoverage = session.occurrenceCoverageLocalStore,
                // A provider, not a value, exactly as the component's KDoc asks: its store flows re-emit
                // on a database write and never on a clock tick. The shell passes its own injected day
                // the same way, so both surfaces answer for the same "today".
                today = { host.today },
            ),
        )
    } else {
        ItemDetailWindowRoot(
            lifecycle = lifecycle,
            ref = ref,
            taskStack = TaskDetailWindowStack(
                DefaultTaskDetailStackComponent(
                    componentContext = DefaultComponentContext(lifecycle),
                    rootId = taskId,
                    taskRepository = session.taskRepository,
                    workingStateEditor = session.workingStateEditor,
                    detailRepository = session.taskDetailRepository,
                    commentRepository = session.commentRepository,
                    itemHistoryRepository = session.itemHistoryRepository,
                    commentWriter = session.commentWriter,
                    currentUserId = session.currentUserId,
                    setDeadline = session.setDeadline,
                    setDeadlineTime = session.setDeadlineTime,
                    setTargetDate = session.setTargetDate,
                    setPriority = session.setPriority,
                    setLabels = session.setLabels,
                ),
            ),
        )
    }
    lifecycle.resume()
    return root
}
