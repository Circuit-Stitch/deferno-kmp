package com.circuitstitch.deferno.feature.calendar

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.OccurrenceAction
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
 * [selectedDay]'s Occurrences + dated items. Gentle by default — there is no "overdue" anywhere; a
 * past unfinished firing simply reads as Scheduled (design-principle #4).
 */
data class CalendarState(
    /** The first day of the visible month (the grid renders this month with full leading/trailing weeks). */
    val visibleMonth: LocalDate,
    val selectedDay: LocalDate,
    val markers: Map<LocalDate, Int> = emptyMap(),
    val agenda: List<CalendarItem> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * The Calendar component (#74): a single-pane time view over Occurrences. It exposes the visible
 * month's [CalendarState.markers] and the selected day's [CalendarState.agenda] from
 * [CalendarRepository] as observable [state], drives occurrence acts through the [OccurrenceEditor]
 * seam (the shell backs it with the command registry — offline-first), and emits
 * [Output.CreateForDay] so the shell's FAB opens **New** pre-dated to the selected day.
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
    today: LocalDate,
    private val tz: String,
    private val output: (CalendarComponent.Output) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : CalendarComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    private val visibleMonth = MutableStateFlow(today.firstOfMonth())
    private val selectedDay = MutableStateFlow(today)
    private val loading = MutableStateFlow(false)

    private val markersFlow: Flow<Map<LocalDate, Int>> = visibleMonth.flatMapLatest { month ->
        val (gridStart, gridEnd) = monthGridWindow(month)
        calendarRepository.observeMarkers(gridStart, gridEnd)
    }
    private val agendaFlow: Flow<List<CalendarItem>> = selectedDay.flatMapLatest { day ->
        calendarRepository.observeDay(day)
    }

    override val state: StateFlow<CalendarState> = combine(
        visibleMonth,
        selectedDay,
        markersFlow,
        agendaFlow,
        loading,
    ) { month, day, markers, agenda, isLoading ->
        CalendarState(visibleMonth = month, selectedDay = day, markers = markers, agenda = agenda, isLoading = isLoading)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), CalendarState(today.firstOfMonth(), today))

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
