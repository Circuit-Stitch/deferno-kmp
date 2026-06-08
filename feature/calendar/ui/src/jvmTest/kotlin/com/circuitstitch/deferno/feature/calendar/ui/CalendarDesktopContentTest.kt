package com.circuitstitch.deferno.feature.calendar.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The desktop Calendar View tests (#74). Two halves:
 *
 *  - the pure [calendarUsesTwoPane] breakpoint (width-driven, never device-type — ADR-0008 G1), pinned
 *    directly the way [com.circuitstitch.deferno.desktop.shell] pins `desktopNavKindFor`;
 *  - a Compose-Multiplatform render test on the JVM-fast path (no device) over [CalendarDesktopContent]:
 *    a **wide** content area lays the month grid **beside** the day agenda (proven by their bounds, not
 *    just their presence), the hoisted reschedule state carries a right-pane "Reschedule" through to a
 *    left-pane grid tap, and a **narrow** window collapses to the shared single-pane stack.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarDesktopContentTest {

    // June 1, 2026 is a Monday — so the agenda heading reads "Monday, June 1".
    private val june = LocalDate(2026, 6, 1)

    private fun item(
        id: String,
        kind: ItemKind?,
        title: String = "Entry $id",
        status: WorkingState = WorkingState.Open,
        date: LocalDate = june,
    ) = CalendarItem(
        id = id,
        taskId = "task-$id",
        seriesId = "series-$id",
        title = title,
        date = date,
        start = Instant.parse("2026-06-01T09:00:00Z"),
        end = Instant.parse("2026-06-01T09:15:00Z"),
        allDay = false,
        status = status,
        kind = kind,
        source = CalendarSource.Deferno,
    )

    // --- the pure breakpoint -----------------------------------------------------------------------

    @Test
    fun belowBreakpoint_usesSinglePane() {
        assertFalse(calendarUsesTwoPane(CALENDAR_TWO_PANE_MIN_WIDTH_DP - 1))
    }

    @Test
    fun atOrAboveBreakpoint_usesTwoPane() {
        assertTrue(calendarUsesTwoPane(CALENDAR_TWO_PANE_MIN_WIDTH_DP))
        assertTrue(calendarUsesTwoPane(1280))
    }

    // --- the rendered layout -----------------------------------------------------------------------

    /**
     * Render [CalendarDesktopContent] over fixed inputs at a forced content width. A width past the
     * breakpoint exercises the two-pane path; one below it the shared single-pane stack.
     */
    private fun ComposeUiTest.render(
        widthDp: Int,
        markers: Map<LocalDate, Int> = emptyMap(),
        agenda: List<CalendarItem> = emptyList(),
        onDaySelected: (LocalDate) -> Unit = {},
        onReschedule: (String, LocalDate) -> Unit = { _, _ -> },
    ) = setContent {
        DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
            Surface(Modifier.fillMaxSize()) {
                CalendarDesktopContent(
                    visibleMonth = june,
                    selectedDay = june,
                    markers = markers,
                    agenda = agenda,
                    onDaySelected = onDaySelected,
                    onPreviousMonth = {},
                    onNextMonth = {},
                    onMark = { _, _ -> },
                    onClear = {},
                    onReschedule = onReschedule,
                    modifier = Modifier.requiredWidth(widthDp.dp).fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun wideWindow_laysTheMonthGridBesideTheDayAgenda() = runComposeUiTest {
        render(widthDp = 1000, agenda = listOf(item("e", ItemKind.Event, title = "Standup")))

        // Both panes are on screen at once: the grid header on the left, the agenda on the right.
        onNodeWithText("June 2026").assertIsDisplayed()
        onNodeWithText("Monday, June 1").assertIsDisplayed()
        onNodeWithText("Standup").assertIsDisplayed()

        // …and they are genuinely side-by-side: the agenda's left edge is past the grid header's right
        // edge (in a single-pane stack the agenda would sit below, not beside).
        val gridHeader = onNodeWithText("June 2026").fetchSemanticsNode().boundsInRoot
        val agendaHeading = onNodeWithText("Monday, June 1").fetchSemanticsNode().boundsInRoot
        assertTrue(
            agendaHeading.left >= gridHeader.right,
            "agenda ($agendaHeading) should be right of the grid header ($gridHeader) in two-pane",
        )
    }

    @Test
    fun wideWindow_reschedulesAcrossPanes_rightPaneAgendaThenLeftPaneGrid() = runComposeUiTest {
        val reschedules = mutableListOf<Pair<String, LocalDate>>()
        render(
            widthDp = 1000,
            agenda = listOf(item("e", ItemKind.Event, title = "Standup")),
            onReschedule = { id, d -> reschedules += id to d },
        )

        // Arm "pick a new day" from the right-pane agenda…
        onNodeWithText("Reschedule").performClick()
        onNodeWithText("Pick a new day", substring = true).assertIsDisplayed()
        // …and complete it with a day tap in the left-pane grid — the hoisted state bridges the panes.
        onNode(hasContentDescription("June 10, no items")).performClick()

        assertEquals(listOf("e" to LocalDate(2026, 6, 10)), reschedules)
    }

    @Test
    fun reschedule_canBeCancelled_thenDayTapsSelectAgain() = runComposeUiTest {
        val reschedules = mutableListOf<Pair<String, LocalDate>>()
        var selected: LocalDate? = null
        render(
            widthDp = 1000,
            agenda = listOf(item("e", ItemKind.Event, title = "Standup")),
            onDaySelected = { selected = it },
            onReschedule = { id, d -> reschedules += id to d },
        )

        // Arm "pick a new day", then cancel it via the banner.
        onNodeWithText("Reschedule").performClick()
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Pick a new day", substring = true).assertDoesNotExist()

        // A subsequent grid day tap now selects the day — it does NOT reschedule (the arming was cleared).
        onNode(hasContentDescription("June 10, no items")).performClick()
        assertEquals(LocalDate(2026, 6, 10), selected)
        assertTrue(reschedules.isEmpty(), "cancelled arming must not reschedule on a later day tap")
    }

    @Test
    fun narrowWindow_collapsesToTheSinglePaneStack() = runComposeUiTest {
        render(widthDp = 500, agenda = listOf(item("e", ItemKind.Event, title = "Standup")))

        onNodeWithText("June 2026").assertIsDisplayed()
        onNodeWithText("Standup").assertIsDisplayed()
        // Stacked: the agenda heading sits below the month header, not beside it.
        val gridHeader = onNodeWithText("June 2026").fetchSemanticsNode().boundsInRoot
        val agendaHeading = onNodeWithText("Monday, June 1").fetchSemanticsNode().boundsInRoot
        assertTrue(
            agendaHeading.top >= gridHeader.bottom,
            "agenda ($agendaHeading) should be below the grid header ($gridHeader) when stacked",
        )
    }
}
