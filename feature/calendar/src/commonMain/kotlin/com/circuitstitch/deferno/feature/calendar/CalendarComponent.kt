package com.circuitstitch.deferno.feature.calendar

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.definition.DefinitionRef
import com.circuitstitch.deferno.core.data.definition.DefinitionStateSource
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.model.CalendarFiring
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.covers
import com.circuitstitch.deferno.core.model.resolveOccurrenceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.coroutines.CoroutineContext

/**
 * Observable state for the Calendar Destination (#74): a month grid + a day agenda over Occurrences.
 * [markers] is the per-day entry count for the visible grid window (the cell dots); [agenda] is the
 * [selectedDay]'s firings + dated items, each already paired with how it went.
 *
 * **[agenda] rows carry a reading, not a status.** Each is a [CalendarFiring]: the feed row plus the
 * derived [com.circuitstitch.deferno.core.model.OccurrenceState] for that (definition, date), resolved
 * here against the stored facts, this device's synced coverage, the definition's light switch and
 * today (ADR-0053 decision 4). It used to be a bare `CalendarItem`, and the chip read that row's
 * `WorkingState` — the *definition's* progress, stamped onto every one of its firings, so a live Habit
 * read "Scheduled" on every day forever and archiving it flipped its whole history to "Done".
 *
 * A past unfinished firing therefore now reads **Missed**, and a day this device has never synced
 * reads **Unknown** rather than being guessed at. That is not a loss of gentleness: gentleness is
 * vocabulary, not suppression (ADR-0053 decision 7) — the catalog fixes the register, and discarding
 * the distinction was never what kept this surface kind.
 */
data class CalendarState(
    /** The first day of the visible month (the grid renders this month with full leading/trailing weeks). */
    val visibleMonth: LocalDate,
    val selectedDay: LocalDate,
    val markers: Map<LocalDate, Int> = emptyMap(),
    val agenda: List<CalendarFiring> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * The Calendar component (#74): a single-pane time view over Occurrences. It exposes the visible
 * month's [CalendarState.markers] from [CalendarRepository] and the selected day's
 * [CalendarState.agenda] — the feed's rows joined to their derived occurrence-state readings — as
 * observable [state], drives occurrence acts through the [OccurrenceEditor] seam (the shell backs it
 * with the command registry — offline-first), and emits [Output.CreateForDay] so the shell's FAB opens
 * **New** pre-dated to the selected day.
 */
interface CalendarComponent {
    val state: StateFlow<CalendarState>

    /** Select the day whose agenda is shown. */
    fun onDaySelected(date: LocalDate)

    /** Page the grid to the previous / next month (re-pointing the markers + pulling that window). */
    fun onShowPreviousMonth()
    fun onShowNextMonth()

    /** Act on a firing in the agenda (offline-first via the [OccurrenceEditor]). */
    fun onMark(itemId: String, action: OccurrenceAction)
    fun onClear(itemId: String)
    fun onReschedule(itemId: String, newDate: LocalDate)

    /** Open **New** pre-dated to the selected day — the shell FAB's intent (AC: "FAB opens New pre-dated"). */
    fun onNewForSelectedDay()

    sealed interface Output {
        /** Open the create surface pre-dated to [date] (the selected day). */
        data class CreateForDay(val date: LocalDate) : Output
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCalendarComponent(
    componentContext: ComponentContext,
    private val calendarRepository: CalendarRepository,
    private val occurrenceEditor: OccurrenceEditor,
    /**
     * The three inputs of the occurrence-state reading that do **not** live on the feed row (ADR-0053
     * decision 4): what the server has on record for a firing, which date ranges this device has
     * actually synced, and each definition's Active/Archived light switch. They are read here, in the
     * component, rather than joined into [CalendarRepository]: the reading is a function of `today`,
     * so it cannot be produced by anything that caches its output — and the repository's whole job is
     * to cache.
     */
    private val occurrenceFacts: OccurrenceFactLocalStore,
    private val occurrenceCoverage: OccurrenceCoverageLocalStore,
    private val definitionStates: DefinitionStateSource,
    /**
     * The user's local today, as a **provider**. It is a constructor-time constant on all four hosts
     * today, and this slice keeps it that way — but the derivation below calls it afresh on every
     * emission, so the day a host hands over a live provider (the timezone/date-rollover work, #392)
     * the readings become live with no retype and no change here.
     *
     * Never a `Clock` read inside this class: a component that reads the clock cannot be tested at a
     * fixed date, and every occurrence-state test in this slice turns on one.
     */
    private val today: () -> LocalDate,
    private val tz: String,
    private val output: (CalendarComponent.Output) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : CalendarComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    /**
     * The day the surface opened on — deliberately a snapshot, and only ever a *seed*. Paging and day
     * selection belong to the person using it, so re-pointing the grid when the date rolls over would
     * yank it out from under them. The readings are what must stay live, and they re-read [today].
     */
    private val openedOn: LocalDate = today()

    private val visibleMonth = MutableStateFlow(openedOn.firstOfMonth())
    private val selectedDay = MutableStateFlow(openedOn)
    private val loading = MutableStateFlow(false)

    private val markersFlow: Flow<Map<LocalDate, Int>> = visibleMonth.flatMapLatest { month ->
        val (gridStart, gridEnd) = monthGridWindow(month)
        calendarRepository.observeMarkers(gridStart, gridEnd)
    }

    /**
     * The selected day's rows, each joined to its reading. Four flows in, one list of
     * [CalendarFiring] out — and nothing in between is stored, so a fact landing from a sync (or from
     * an optimistic write) re-emits the whole agenda with fresh readings without the calendar cache
     * being touched at all.
     */
    private val agendaFlow: Flow<List<CalendarFiring>> = selectedDay.flatMapLatest { day ->
        combine(
            calendarRepository.observeDay(day),
            occurrenceFacts.observeOn(day),
            occurrenceCoverage.observeCovering(day),
            definitionStates.observeAll(),
        ) { items, facts, coverage, states ->
            resolveAgenda(items, facts, coverage, states, today())
        }
    }

    // Five arms exactly, which is the ceiling of Kotlin's TYPED `combine`. A sixth silently binds the
    // vararg overload, whose lambda takes an `Array<Any?>` — the compiler stops checking the arms and
    // a mis-ordered one becomes a runtime cast. Everything the agenda needs is joined one level down,
    // inside agendaFlow, precisely to keep this at five.
    override val state: StateFlow<CalendarState> = combine(
        visibleMonth,
        selectedDay,
        markersFlow,
        agendaFlow,
        loading,
    ) { month, day, markers, agenda, isLoading ->
        CalendarState(visibleMonth = month, selectedDay = day, markers = markers, agenda = agenda, isLoading = isLoading)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), CalendarState(openedOn.firstOfMonth(), openedOn))

    init {
        // Pull the visible month's window on open so the grid + agenda have data (ADR-0001).
        refreshVisibleMonth()
    }

    override fun onDaySelected(date: LocalDate) {
        selectedDay.value = date
    }

    override fun onShowPreviousMonth() {
        visibleMonth.value = visibleMonth.value.minus(1, DateTimeUnit.MONTH)
        refreshVisibleMonth()
    }

    override fun onShowNextMonth() {
        visibleMonth.value = visibleMonth.value.plus(1, DateTimeUnit.MONTH)
        refreshVisibleMonth()
    }

    override fun onMark(itemId: String, action: OccurrenceAction) {
        scope.launch { occurrenceEditor.mark(itemId, action) }
    }

    override fun onClear(itemId: String) {
        scope.launch { occurrenceEditor.clear(itemId) }
    }

    override fun onReschedule(itemId: String, newDate: LocalDate) {
        scope.launch { occurrenceEditor.reschedule(itemId, newDate) }
    }

    override fun onNewForSelectedDay() {
        output(CalendarComponent.Output.CreateForDay(selectedDay.value))
    }

    private fun refreshVisibleMonth() {
        val (gridStart, gridEnd) = monthGridWindow(visibleMonth.value)
        scope.launch {
            loading.value = true
            try {
                calendarRepository.refreshWindow(gridStart, gridEnd, tz)
            } finally {
                loading.value = false
            }
        }
    }
}

/**
 * Pair each agenda row with how its firing went — the join ADR-0053 decision 4 describes, kept as a
 * pure function so it is exercisable at any date with no coroutines in the way.
 *
 * The key is `(kind, taskId, date)`. [CalendarItem.taskId] *is* the definition id: the KDoc on it says
 * so ("the chain Head, and the id the occurrence endpoints address"), and it is the same id
 * `OccurrenceTargets.of` builds an outbox key from — so an optimistically-written fact and the row it
 * belongs to meet without any translation. Using [CalendarItem.seriesId] here would be the classic
 * mistake: it identifies *which* series a firing belongs to and is never an address.
 *
 * A row that is not an actionable firing — a one-off dated Task, an unresolved-kind row, a synced
 * external event — gets a `null` reading and keeps rendering from its own [CalendarItem.status]. That
 * status is a genuine fact about *that* item; it is only meaningless when stamped onto a firing.
 */
internal fun resolveAgenda(
    items: List<CalendarItem>,
    facts: List<OccurrenceFact>,
    coverage: List<OccurrenceCoverage>,
    definitionStates: Map<DefinitionRef, DefinitionState>,
    today: LocalDate,
): List<CalendarFiring> {
    // Keyed on the full firing identity rather than on (kind, definitionId): `facts` arrives scoped to
    // one day, but nothing in the type says so, and a lookup that silently ignored the date would
    // report yesterday's resolution on today's row.
    val byFiring = facts.associateBy { Triple(it.kind, it.definitionId, it.date) }
    return items.map { item ->
        val kind = item.kind
        if (kind == null || !item.isActionableOccurrence) return@map CalendarFiring(item, occurrence = null)
        CalendarFiring(
            item = item,
            occurrence = resolveOccurrenceState(
                fact = byFiring[Triple(kind, item.taskId, item.date)],
                covered = coverage.covers(kind, item.taskId, item.date),
                definitionState = definitionStates[DefinitionRef(kind, item.taskId)],
                date = item.date,
                today = today,
            ),
        )
    }
}

/** The first day of this date's month. */
internal fun LocalDate.firstOfMonth(): LocalDate = LocalDate(year, month, 1)

/**
 * The 6-week (42-day) grid window covering [monthStart]'s month with full leading/trailing weeks,
 * Monday-start (ISO). Half-open `[gridStart, gridEnd)` — matches the feed window semantics and gives the
 * grid's padding cells (the previous/next month days) their markers too.
 */
internal fun monthGridWindow(monthStart: LocalDate): Pair<LocalDate, LocalDate> {
    val lead = monthStart.dayOfWeek.isoDayNumber - 1
    val gridStart = monthStart.minus(lead, DateTimeUnit.DAY)
    return gridStart to gridStart.plus(42, DateTimeUnit.DAY)
}
