package com.circuitstitch.deferno.feature.calendar

import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * In-memory [CalendarRepository] for component tests: a [MutableStateFlow] of the grid markers and a
 * map of per-day agendas the tests mutate, plus recorded `refreshWindow`/`reconcile` calls.
 */
class FakeCalendarRepository : CalendarRepository {
    val markers = MutableStateFlow<Map<LocalDate, Int>>(emptyMap())
    private val agendas = MutableStateFlow<Map<LocalDate, List<CalendarItem>>>(emptyMap())

    val refreshArgs = mutableListOf<Triple<LocalDate, LocalDate, String>>()
    var reconcileCount = 0
        private set

    fun setAgenda(date: LocalDate, items: List<CalendarItem>) {
        agendas.value = agendas.value + (date to items)
    }

    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> = markers
    override fun observeDay(date: LocalDate): Flow<List<CalendarItem>> = agendas.map { it[date] ?: emptyList() }

    override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {
        refreshArgs += Triple(from, to, tz)
    }

    override suspend fun reconcile() {
        reconcileCount++
    }
}

/** Recording [OccurrenceEditor] for the component tests. */
class RecordingOccurrenceEditor : OccurrenceEditor {
    val calls = mutableListOf<String>()
    override suspend fun mark(itemId: String, action: com.circuitstitch.deferno.core.model.OccurrenceAction) {
        calls += "mark:$itemId:$action"
    }
    override suspend fun clear(itemId: String) { calls += "clear:$itemId" }
    override suspend fun reschedule(itemId: String, newDate: LocalDate) { calls += "reschedule:$itemId:$newDate" }
}

internal fun calendarItem(
    id: String,
    date: LocalDate,
    seriesId: String? = "hab-1",
    kind: ItemKind? = ItemKind.Habit,
    title: String = "Morning stretch",
    status: WorkingState = WorkingState.Open,
) = CalendarItem(
    id = id,
    taskId = "task-$id",
    seriesId = seriesId,
    title = title,
    date = date,
    start = Instant.parse("${date}T09:00:00Z"),
    end = Instant.parse("${date}T09:15:00Z"),
    allDay = false,
    status = status,
    kind = kind,
    source = CalendarSource.Deferno,
)
