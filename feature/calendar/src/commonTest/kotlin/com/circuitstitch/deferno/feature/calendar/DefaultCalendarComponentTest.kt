package com.circuitstitch.deferno.feature.calendar

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.OccurrenceAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private val TODAY = LocalDate(2026, 6, 15)
private const val TZ = "America/New_York"

private fun TestScope.calendarComponent(
    repo: FakeCalendarRepository,
    editor: OccurrenceEditor = RecordingOccurrenceEditor(),
    output: (CalendarComponent.Output) -> Unit = {},
) = DefaultCalendarComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    calendarRepository = repo,
    occurrenceEditor = editor,
    today = TODAY,
    tz = TZ,
    output = output,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drives the scheduler past the init fetch.
class DefaultCalendarComponentTest {

    @Test
    fun opensOnTodaysMonthAndPullsTheGridWindow() = runTest {
        val repo = FakeCalendarRepository()
        val component = calendarComponent(repo)
        advanceUntilIdle()

        // The initial state opens on this month, selected = today.
        assertEquals(LocalDate(2026, 6, 1), component.state.value.visibleMonth)
        assertEquals(TODAY, component.state.value.selectedDay)
        // On open it refreshes exactly the 6-week grid window for June 2026 (derived, not hard-coded).
        val (gridStart, gridEnd) = monthGridWindow(LocalDate(2026, 6, 1))
        assertEquals(listOf(Triple(gridStart, gridEnd, TZ)), repo.refreshArgs)
    }

    @Test
    fun selectingADayShowsItsAgenda_andMarkersSurface() = runTest {
        val day = LocalDate(2026, 6, 8)
        val repo = FakeCalendarRepository()
        repo.markers.value = mapOf(day to 2)
        repo.setAgenda(day, listOf(calendarItem("ce-1", day)))
        val component = calendarComponent(repo)

        component.state.test {
            // The visible month's markers surface (await past the stateIn seed).
            var s = awaitItem()
            while (s.markers.isEmpty()) s = awaitItem()
            assertEquals(mapOf(day to 2), s.markers)

            // Selecting a day shows its agenda.
            component.onDaySelected(day)
            while (s.selectedDay != day || s.agenda.isEmpty()) s = awaitItem()
            assertEquals(listOf("ce-1"), s.agenda.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pagingToThePreviousMonthRePointsAndPullsThatWindow() = runTest {
        val repo = FakeCalendarRepository()
        val component = calendarComponent(repo)
        advanceUntilIdle() // the on-open refresh of this month
        repo.refreshArgs.clear()

        component.onShowPreviousMonth()
        advanceUntilIdle()

        // The previous month's grid window is pulled. The window itself encodes the paged-to month
        // (derived from the same pure helper the SUT uses), so the assertion needs no collected state.
        val (gridStart, gridEnd) = monthGridWindow(LocalDate(2026, 5, 1))
        assertEquals(listOf(Triple(gridStart, gridEnd, TZ)), repo.refreshArgs)
    }

    @Test
    fun markRoutesToTheOccurrenceEditor() = runTest {
        val repo = FakeCalendarRepository()
        val editor = RecordingOccurrenceEditor()
        val component = calendarComponent(repo, editor)

        component.onMark("ce-1", OccurrenceAction.Complete)
        component.onReschedule("ce-2", LocalDate(2026, 6, 20))
        component.onClear("ce-3")
        advanceUntilIdle()

        assertEquals(
            listOf("mark:ce-1:Complete", "reschedule:ce-2:2026-06-20", "clear:ce-3"),
            editor.calls,
        )
    }

    @Test
    fun theFabEmitsCreateForTheSelectedDay() = runTest {
        val repo = FakeCalendarRepository()
        val outputs = mutableListOf<CalendarComponent.Output>()
        val component = calendarComponent(repo, output = { outputs += it })

        component.onDaySelected(LocalDate(2026, 6, 8))
        component.onNewForSelectedDay()

        assertEquals(
            listOf<CalendarComponent.Output>(CalendarComponent.Output.CreateForDay(LocalDate(2026, 6, 8))),
            outputs,
        )
    }
}
