package com.circuitstitch.deferno.demo

import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * In-memory [CalendarRepository] **test fake** for the Calendar Destination's screenshot/View tests
 * (#74). Reads are local Flows over fixed sample data — no network or database; `refreshWindow` /
 * `reconcile` are no-ops (the sample is the source of truth). The real app reads the DI-provided
 * OfflineCalendarRepository (ADR-0014); this stays a test fixture.
 */
internal class DemoCalendarRepository(
    private val markers: Map<LocalDate, Int> = emptyMap(),
    private val agenda: Map<LocalDate, List<CalendarItem>> = emptyMap(),
) : CalendarRepository {
    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> =
        MutableStateFlow(markers.filterKeys { it >= from && it < to })

    override fun observeDay(date: LocalDate): Flow<List<CalendarItem>> =
        MutableStateFlow(agenda[date] ?: emptyList())

    override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {}
    override suspend fun reconcile() {}
}

/**
 * Sample Calendar content for the screenshot tests (#74): a small, calm month (design-principles.md) —
 * a few days with marker dots, and a selected day whose agenda shows a Habit / Chore / Event firing
 * plus a dated Task, so the kind-aware action set + the gentle status labels render in the baseline.
 */
internal object SampleCalendar {
    val day: LocalDate = LocalDate(2026, 6, 15)

    private fun item(
        id: String,
        kind: ItemKind?,
        seriesId: String?,
        title: String,
        status: WorkingState = WorkingState.Open,
    ) = CalendarItem(
        id = id,
        taskId = "task-$id",
        seriesId = seriesId,
        title = title,
        date = day,
        start = Instant.parse("2026-06-15T09:00:00Z"),
        end = Instant.parse("2026-06-15T09:30:00Z"),
        allDay = false,
        status = status,
        kind = kind,
        source = CalendarSource.Deferno,
    )

    val agenda: Map<LocalDate, List<CalendarItem>> = mapOf(
        day to listOf(
            item("h1", ItemKind.Habit, "hab-1", "Morning stretch"),
            item("c1", ItemKind.Chore, "cho-1", "Water the plants", status = WorkingState.Done),
            item("e1", ItemKind.Event, "evt-1", "Team standup"),
            // A one-off dated Task (no series) — rendered, read-only (acted on in Tasks).
            item("t1", kind = null, seriesId = null, title = "Pay the rent"),
        ),
    )

    val markers: Map<LocalDate, Int> = mapOf(
        LocalDate(2026, 6, 3) to 1,
        LocalDate(2026, 6, 8) to 2,
        day to 4,
        LocalDate(2026, 6, 22) to 1,
    )
}
