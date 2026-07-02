package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.agent.ImpedimentClassifier
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The shell-level holder the **Breakdown** overlay renders (Deferno#525). It owns the target item — observed
 * reactively, since its title + body are the classifier's *item context* — the offline-first
 * [BreakdownActions] the flow applies, and the dismiss callback.
 *
 * On **Android/desktop** it also owns the deterministic [BreakdownEngine] (built once via [bind]). The engine
 * lives here — on the retained shell component — rather than in the Compose layer, so an in-progress
 * conversation, its depth-first worklist, and the subtasks it has already created all survive Activity
 * recreation (rotation, font-scale, locale, multi-window) like every other overlay's state. **iOS** ignores
 * [engine]/[bind]/the intents and renders its SwiftUI twin (a native Swift state machine + Foundation Models
 * classifier) over [actions]/[target] instead.
 */
interface BreakdownComponent {
    /**
     * The stuck item being broken down. A `String`, not [TaskId]: it crosses the SKIE bridge to the iOS
     * Swift engine, and inline value classes are header-erased there.
     */
    val taskId: String

    /** The target item, observed live — its title/body seed the engine's root context; `null` until the local row resolves. */
    val target: StateFlow<Task?>

    /** The offline-first structural moves the engine applies (capture child / drop / add-to-plan). */
    val actions: BreakdownActions

    /**
     * The deterministic engine driving the **Android/desktop Compose** chat — retained here (not in the
     * Compose layer) so it survives config change. `null` until [bind] is called and the [target] resolves;
     * stays `null` on iOS (which never calls [bind]).
     */
    val engine: StateFlow<BreakdownEngine?>

    /**
     * Provide the on-device [classifier] — plus the localized fallback [prerequisites] titles the engine may
     * persist as server-synced subtasks — so the [engine] can be built once the [target] resolves.
     * Idempotent, so the Compose screen may call it on every (re)composition; only the first call builds.
     * Android/desktop only; iOS never calls it (its Swift engine owns classification).
     */
    fun bind(
        classifier: ImpedimentClassifier,
        prerequisites: BreakdownEngine.Prerequisites = BreakdownEngine.Prerequisites(),
    )

    /** Submit the person's "what's stopping you?" answer. Launched on the component scope; a no-op until [bind] builds the engine. */
    fun submit(answer: String)

    /** Answer the drop confirmation (PRD #26): an explicit yes drops, no keeps it and re-asks. */
    fun confirmDrop(yes: Boolean)

    /** The Ready terminal's "Add to plan" move (PRD #21). */
    fun addRootToPlan()

    /** Dismiss the overlay — Close, or the terminal the flow reaches (Ready / dropped / bailed). */
    fun onClose()
}

/** Production [BreakdownComponent]: observes [taskId] from the Account's [TaskRepository] and holds the retained engine. */
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

    private val _engine = MutableStateFlow<BreakdownEngine?>(null)
    override val engine: StateFlow<BreakdownEngine?> = _engine.asStateFlow()

    // Guards bind() to a single engine build even though Compose may call it on every (re)composition.
    // Touched only from the Compose main thread (bind is called from a LaunchedEffect).
    private var building = false

    override fun bind(classifier: ImpedimentClassifier, prerequisites: BreakdownEngine.Prerequisites) {
        if (building) return
        building = true
        scope.launch {
            // Snapshot the root once the local row resolves — a later title edit doesn't rebuild the engine,
            // so the flow stays on the item you opened (parity with the iOS @StateObject snapshot).
            val task = target.filterNotNull().first()
            _engine.value = BreakdownEngine(
                root = BreakdownEngine.ItemContext(id = taskId, title = task.title, notes = task.description),
                classifier = classifier,
                moves = actions,
                prerequisites = prerequisites,
            )
        }
    }

    // Intents run on the component scope (retained across config change), so an in-flight classification
    // survives a rotation rather than stranding the engine in its Working phase.
    override fun submit(answer: String) {
        scope.launch { _engine.value?.submit(answer) }
    }

    override fun confirmDrop(yes: Boolean) {
        scope.launch { _engine.value?.confirmDrop(yes) }
    }

    override fun addRootToPlan() {
        scope.launch { _engine.value?.addRootToPlan() }
    }

    override fun onClose() = onCloseRequested()
}
