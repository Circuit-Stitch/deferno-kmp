package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.definition.DefinitionRepository
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.RecurringDefinition
import com.circuitstitch.deferno.core.model.SeriesChain
import com.circuitstitch.deferno.core.model.TodayOccurrence
import com.circuitstitch.deferno.core.model.dayFiring
import com.circuitstitch.deferno.core.model.factDateFor
import com.circuitstitch.deferno.core.model.readTodayOccurrence
import com.circuitstitch.deferno.core.model.toItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

    /**
     * The definition, the day's coverage and the detail-read extras — everything the fact's lookup date
     * is *derived from*, but not the fact itself. The grid has to be expanded before the right date to
     * query is known, so the fact subscription hangs off this rather than sitting beside it.
     */
    private data class Inputs(
        val definition: RecurringDefinition?,
        val covering: List<OccurrenceCoverage>,
        val extras: Extras,
    )

    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest — re-key the fact query when the slot moves.
    override val state: StateFlow<DefinitionDetailState> = combine(
        definitionRepository.observe(ref),
        occurrenceCoverage.observeCovering(today()),
        extras,
        ::Inputs,
    ).flatMapLatest { inputs ->
        val definition = inputs.definition
        val now = today()
        val firing = dayFiring(definition?.recurrence, definition?.series, now)
        // `factDateFor` is the silent-miss guard: when the grid moved an instance onto today, its fact
        // is filed under the slot it came FROM, so querying today would read a resolved firing as
        // unresolved. It is also why this is a flatMapLatest and not a fourth `combine` arm — the
        // date to observe is a function of the expanded grid, so it cannot be known when the flows are
        // wired, and observing today's date while *reading* the slot's would leave the row stale until
        // something else re-emitted (the fact table is written by the plan and the calendar, and a
        // detached macOS detail window is explicitly expected to track those live, ADR-0033).
        val factDate = firing.factDateFor(now)
        val covered = inputs.covering.any { it.kind == ref.kind && it.definitionId == ref.id && it.covers(factDate) }

        occurrenceFacts.observe(ref.kind, ref.id, factDate).map { fact ->
            DefinitionDetailState(
                ref = ref,
                definition = definition,
                item = definition?.toItem(),
                isHydrating = inputs.extras.isHydrating,
                // The already-expanded overload: `firing` is needed above for the slot correction, and
                // expanding the grid a second time per emission would walk the rule slot by slot again.
                today = readTodayOccurrence(
                    firing = firing,
                    definitionState = definition?.definitionState,
                    fact = fact,
                    covered = covered,
                    today = now,
                ),
                eras = inputs.extras.eras,
                originLabel = inputs.extras.originLabel,
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DefinitionDetailState(ref = ref, isHydrating = true))

    init {
        scope.launch {
            // Best-effort: offline this returns null and the cached row still renders. It is also the
            // only thing that writes Occurrence coverage for this definition, so without it today's
            // reading is permanently Unknown — see DefinitionRepository.hydrate.
            val fetched = definitionRepository.hydrate(ref)
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
