package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.CoroutineContext

/**
 * The shell-level holder the iOS-native **Breakdown** overlay renders (Deferno#525). Breakdown's
 * orchestration and UI are native Swift (a deterministic state machine + a Foundation Models
 * answer-classifier); this Kotlin side is a thin holder so the overlay slot stays uniform with every
 * other [MainShellComponent.OverlayChild]. It exposes the target item — observed reactively, since its
 * title + body are the classifier's *item context* — the offline-first [BreakdownActions] the Swift
 * state machine applies, and the dismiss callback.
 */
interface BreakdownComponent {
    /**
     * The stuck item being broken down. A `String`, not [TaskId]: it crosses the SKIE bridge to the
     * Swift engine (which feeds it back to [actions]), and inline value classes are header-erased there.
     */
    val taskId: String

    /** The target item, observed live — its title/body are the classifier's context; `null` until the local row resolves. */
    val target: StateFlow<Task?>

    /** The offline-first structural moves the native state machine applies (capture child / drop / add-to-plan). */
    val actions: BreakdownActions

    /** Dismiss the overlay — Close, or the terminal the Swift flow reaches (Ready / dropped / bailed). */
    fun onClose()
}

/** Production [BreakdownComponent]: observes [taskId] from the Account's [TaskRepository]. */
class DefaultBreakdownComponent(
    componentContext: ComponentContext,
    override val taskId: String,
    taskRepository: TaskRepository,
    override val actions: BreakdownActions,
    private val onCloseRequested: () -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : BreakdownComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    override val target: StateFlow<Task?> =
        taskRepository.observeTask(TaskId(taskId))
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    override fun onClose() = onCloseRequested()
}
