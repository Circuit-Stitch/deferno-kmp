package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.definition.DefinitionRepository
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.RecurringDefinition
import com.circuitstitch.deferno.core.model.SeriesChain
import com.circuitstitch.deferno.core.model.TodayOccurrence
import com.circuitstitch.deferno.core.model.dayFiring
import com.circuitstitch.deferno.core.model.factDateFor
import com.circuitstitch.deferno.core.model.readTodayOccurrence
import com.circuitstitch.deferno.core.model.toItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext

/**
 * The read-only detail for a recurring definition — a [[Habit]], [[Chore]] or [[Event]] (#383).
 *
 * **Deliberately not a widening of [TaskDetailComponent].** The issue's shape ("one component with
 * per-kind conditional slots, not four parallel screens") describes the webui's `ItemDetailView`, and
 * it does not carry over to this component, for three reasons that are all facts about this codebase
 * rather than preferences:
 *
 * 1. `TaskDetailComponent.taskId` is read as **SwiftUI view identity** — `BridgeKt.detailKey` feeds
 *    `.id(…)` at eight Swift call sites. Retyping it would break identity *silently* (stale state
 *    across a re-key) rather than at compile time.
 * 2. Its surface is Task-semantic throughout: `onSetWorkingState(WorkingState)` — a definition has a
 *    [[Definition state]] light switch, which is a different axis and can never be "done" — plus a
 *    subtask outline typed `SubtaskRow(task: Task)`, comments, attachments and Breakdown. A merged
 *    interface would be a dozen no-op arms around a `task: Task?` that is permanently `null` for the
 *    three recurring kinds, which renders the not-found empty state: the exact bug being fixed.
 * 3. `DefaultTaskDetailComponent.init` unconditionally fires five Task-scoped side effects, starting
 *    with `taskRepository.hydrate(taskId)` → `GET /tasks/{habitId}`. A recurring id down that path
 *    404s, which the outbox posture reads as success.
 *
 * Two components, not four: [TasksComponent.DetailChild] is the sealed choice between them, mirroring
 * `MainShellComponent.PlanChild`, which is already exactly this shape.
 *
 * **Read-only by design, for this slice.** Every write on a recurring definition — the rule, per-field
 * patches, delete — belongs to #378/#388/#389, whose seams are still `TaskId`-typed. The one write that
 * already works kind-neutrally, the Archive/Restore light switch, lives in the tree's command menu
 * (#299) and is deliberately not duplicated here.
 */
interface DefinitionDetailComponent {

    /** The addressed definition. Kind-carrying and raw-id — never a `TaskId`. */
    val ref: ItemRef

    val state: StateFlow<DefinitionDetailState>

    fun onCloseClicked()

    sealed interface Output {
        data object Closed : Output
    }
}

/**
 * What the recurring detail renders.
 *
 * [definition] is the cached record — the only source of `description` and `labels`, which the [Item]
 * projection deliberately does not carry. [item] is that same definition projected back to [Item] so
 * the **shared** display readings (`recurrenceReading`, `recurrenceCursor`) phrase cadence and next-due,
 * rather than this state growing a fifth copy of cadence normalisation.
 *
 * [today] is a **reading**, recomputed on every emission and never stored — see [TodayOccurrence].
 * [eras] rides the detail read and is likewise never cached; #395 renders it, this issue only carries it.
 */
data class DefinitionDetailState(
    val ref: ItemRef,
    val definition: RecurringDefinition? = null,
    val item: Item? = null,
    val isHydrating: Boolean = false,
    val today: TodayOccurrence = TodayOccurrence.Unknown,
    val eras: SeriesChain? = null,
    val originLabel: String? = null,
) {
    /**
     * Whether this device knows nothing about the definition — the honest "not found" state, which is
     * only worth showing once a hydrate has finished failing to produce it.
     */
    val isMissing: Boolean get() = definition == null && !isHydrating
}

class DefaultDefinitionDetailComponent(
    componentContext: ComponentContext,
    override val ref: ItemRef,
    private val definitionRepository: DefinitionRepository,
    private val occurrenceFacts: OccurrenceFactLocalStore,
    private val occurrenceCoverage: OccurrenceCoverageLocalStore,
    /**
     * The reader's local **today**, as a provider — never a clock read inside this class.
     *
     * It is a lambda and not a value for the same reason `recurrenceSummary` takes a zone: a reading
     * baked at construction would still claim "Scheduled" tomorrow, because the store flows re-emit on
     * a database write and never on a clock tick. This mirrors `DefaultCalendarComponent`'s contract, so
     * #392 can make the zone dynamic with no retype here.
     */
    private val today: () -> LocalDate,
    private val output: (DefinitionDetailComponent.Output) -> Unit = {},
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : DefinitionDetailComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)

    /** The detail-read-only half plus the in-flight flag — folded into one flow to stay inside combine's arity. */
    private data class Extras(
        val isHydrating: Boolean = true,
        val eras: SeriesChain? = null,
        val originLabel: String? = null,
    )

    private val extras = MutableStateFlow(Extras())

    override val state: StateFlow<DefinitionDetailState> = combine(
        definitionRepository.observe(ref),
        // The fact for today's date. A rescheduled firing keeps its identity at the slot it moved FROM,
        // so the lookup date is corrected below once the grid is known — this is the common-case read.
        occurrenceFacts.observe(ref.kind, ref.id, today()),
        occurrenceCoverage.observeCovering(today()),
        extras,
    ) { definition, factToday, covering, ex ->
        val now = today()
        val firing = dayFiring(definition?.recurrence, definition?.series, now)
        // `factDateFor` is the silent-miss guard: when the grid moved an instance onto today, its fact
        // is filed under the slot it came from, and querying today would read as unresolved.
        val factDate = firing.factDateFor(now)
        val fact = if (factDate == now) factToday else occurrenceFacts.get(ref.kind, ref.id, factDate)
        val covered = covering.any { it.kind == ref.kind && it.definitionId == ref.id && it.covers(factDate) }

        DefinitionDetailState(
            ref = ref,
            definition = definition,
            item = definition?.toItem(),
            isHydrating = ex.isHydrating,
            // The already-expanded overload: `firing` is needed above for the slot correction, and
            // expanding the grid a second time per emission would walk the rule slot by slot again.
            today = readTodayOccurrence(
                firing = firing,
                definitionState = definition?.definitionState,
                fact = fact,
                covered = covered,
                today = now,
            ),
            eras = ex.eras,
            originLabel = ex.originLabel,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DefinitionDetailState(ref = ref, isHydrating = true))

    init {
        scope.launch {
            // Best-effort: offline this returns null and the cached row still renders. It is also the
            // only thing that writes Occurrence coverage for this definition, so without it today's
            // reading is permanently Unknown — see DefinitionRepository.hydrate.
            val fetched = definitionRepository.hydrate(ref, today())
            extras.update {
                it.copy(
                    isHydrating = false,
                    eras = fetched?.chain ?: it.eras,
                    originLabel = fetched?.originLabel ?: it.originLabel,
                )
            }
        }
    }

    override fun onCloseClicked() {
        output(DefinitionDetailComponent.Output.Closed)
    }

    private companion object {
        /** Matches the Task detail's own subscription grace period. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
