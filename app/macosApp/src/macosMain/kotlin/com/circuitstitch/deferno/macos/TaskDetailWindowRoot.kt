package com.circuitstitch.deferno.macos

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.tasks.DefaultTaskDetailStackComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailStackComponent
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * The Swift-facing handle for one **detached Task detail window** (#196, ADR-0033). It owns the window's
 * own Essenty [LifecycleRegistry] (resumed at open, [destroy]ed when SwiftUI tears the scene down — the
 * per-window lifecycle, mirroring [DefernoRoot]) and exposes the [TaskDetailStackComponent]'s push/pop
 * stack as SKIE-bridged `StateFlow` mirrors ([activeDetail] + [canGoBack]) so SwiftUI never touches
 * Decompose generics.
 *
 * It is built from the **live** [com.circuitstitch.deferno.shell.AccountSession] the main shell already
 * holds (via [openTaskDetailWindow]), not a fresh `AccountComponent` — so the window and the main shell
 * share one SQLite driver and a write in either re-emits into the other's query Flow (the cross-window
 * live-sync AC; ADR-0033 deviation from the issue's per-call `createAccountComponent`).
 */
class TaskDetailWindowRoot internal constructor(
    private val lifecycle: LifecycleRegistry,
    /** The raw root Task id — the SwiftUI scene value, used for the window title fetch + dedupe. */
    val rootTaskId: String,
    private val component: TaskDetailStackComponent,
) {
    /** The window's foreground detail page + whether a level can be popped (the Back control's gate). */
    val activeDetail: StateFlow<TaskDetailComponent> = component.activeDetail
    val canGoBack: StateFlow<Boolean> = component.canGoBack

    /** Pop one drilled level (the Back control); false at the root, where the window's chrome closes it. */
    fun onBack(): Boolean = component.onBack()

    /** Tear down the window's component tree when its SwiftUI scene goes away (no leaks across open/close). */
    fun destroy() {
        lifecycle.destroy()
    }
}

/**
 * Open a detached detail window rooted at [idValue] over the **active** account session (#196). Returns
 * `null` when signed out (the Auth shell — [RootComponent.activeAccountSession] is null) or when [idValue]
 * is blank, so the Swift opener simply does nothing in those cases (detail windows are unavailable when
 * signed out). `createSubtask` is left at its no-op default — add-subtask in a window is #197.
 */
fun openTaskDetailWindow(root: RootComponent, idValue: String): TaskDetailWindowRoot? {
    val session = root.activeAccountSession ?: return null
    val taskId = idValue.takeIf { it.isNotBlank() }?.let(::TaskId) ?: return null

    val lifecycle = LifecycleRegistry()
    val component = DefaultTaskDetailStackComponent(
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
        setLabels = session.setLabels,
    )
    lifecycle.resume()
    return TaskDetailWindowRoot(lifecycle, taskId.value, component)
}
