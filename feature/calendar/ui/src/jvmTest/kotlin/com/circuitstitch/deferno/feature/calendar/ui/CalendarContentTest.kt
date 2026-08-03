package com.circuitstitch.deferno.feature.calendar.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
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
import kotlin.time.Instant

/**
 * The Calendar View render test (#74) — a Compose-Multiplatform UI test on the JVM-fast path (no
 * device). It drives the stateless [CalendarContent] with fixed inputs + intent spies, covering: the
 * month grid (label + day-tap selection), the day agenda's **kind-aware** action set (a habit is binary
 * — no start/skip; every recurring kind can be rescheduled since #380), the grid-tap reschedule, and —
 * crucially — the absence of any shaming vocabulary (design-principle #4). The component logic is
 * unit-tested in feature:calendar.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarContentTest {

    private val june = LocalDate(2026, 6, 1)

    private fun item(
        id: String,
        kind: ItemKind?,
        seriesId: String? = "series-$id",
        title: String = "Entry $id",
        status: WorkingState = WorkingState.Open,
        date: LocalDate = june,
        source: CalendarSource = CalendarSource.Deferno,
    ) = CalendarItem(
        id = id,
        // The item id and the series id are distinct values on a real firing (#380) — the act path
        // addresses `taskId`; `seriesId` only marks the row as a firing.
        taskId = "task-$id",
        seriesId = seriesId,
        title = title,
        date = date,
        start = Instant.parse("2026-06-01T09:00:00Z"),
        end = Instant.parse("2026-06-01T09:15:00Z"),
        allDay = false,
        status = status,
        kind = kind,
        source = source,
    )

    private fun ComposeUiTest.render(
        selectedDay: LocalDate = june,
        markers: Map<LocalDate, Int> = emptyMap(),
        agenda: List<CalendarItem> = emptyList(),
        onDaySelected: (LocalDate) -> Unit = {},
        onMark: (String, OccurrenceAction) -> Unit = { _, _ -> },
        onClear: (String) -> Unit = {},
        onReschedule: (String, LocalDate) -> Unit = { _, _ -> },
    ) = setContent {
        DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
            Surface(Modifier.fillMaxSize()) {
                CalendarContent(
                    visibleMonth = june,
                    selectedDay = selectedDay,
                    markers = markers,
                    agenda = agenda,
                    onDaySelected = onDaySelected,
                    onPreviousMonth = {},
                    onNextMonth = {},
                    onMark = onMark,
                    onClear = onClear,
                    onReschedule = onReschedule,
                )
            }
        }
    }

    @Test
    fun monthLabelShowsAndTappingADayCellSelectsIt() = runComposeUiTest {
        var selected: LocalDate? = null
        render(markers = mapOf(LocalDate(2026, 6, 8) to 2), onDaySelected = { selected = it })

        onNodeWithText("June 2026").assertIsDisplayed()
        // The grid cell exposes a count marker via its accessibility label; tapping it selects the day.
        onNode(hasContentDescription("June 8, 2 items")).performClick()
        assertEquals(LocalDate(2026, 6, 8), selected)
    }

    @Test
    fun habitRowOffersDoneClearAndReschedule_butNoStartOrSkip() = runComposeUiTest {
        render(agenda = listOf(item("h", ItemKind.Habit, title = "Stretch")))

        onNodeWithText("Done").assertIsDisplayed()
        onNodeWithText("Clear").assertIsDisplayed()
        // #380: habit reschedule ships server-side (`reschedule_recurring_occurrence`), so it is offered.
        onNodeWithText("Reschedule").assertIsDisplayed()
        // A habit firing is still binary — no start / skip.
        onNodeWithText("Start").assertDoesNotExist()
        onNodeWithText("Skip").assertDoesNotExist()
    }

    @Test
    fun choreRowOffersStartDoneSkipClearAndReschedule() = runComposeUiTest {
        render(agenda = listOf(item("c", ItemKind.Chore, title = "Dishes")))

        onNodeWithText("Start").assertIsDisplayed()
        onNodeWithText("Done").assertIsDisplayed()
        onNodeWithText("Skip").assertIsDisplayed()
        onNodeWithText("Clear").assertIsDisplayed()
        // #380: chore reschedule shares the same server handler as habit and event.
        onNodeWithText("Reschedule").assertIsDisplayed()
    }

    @Test
    fun anExternalRowAndAnUnresolvedKindRowOfferNoActionsAtAll() = runComposeUiTest {
        // Gentle degradation: a synced Google row and a firing whose kind we couldn't resolve both
        // render read-only rather than offering a verb that would post to the wrong place (#380).
        render(
            agenda = listOf(
                item("g", ItemKind.Event, title = "Synced meeting", source = CalendarSource.External),
                item("u", kind = null, title = "Unknown series"),
            ),
        )

        onNodeWithText("Synced meeting").assertIsDisplayed()
        onNodeWithText("Unknown series").assertIsDisplayed()
        for (verb in listOf("Start", "Done", "Skip", "Clear", "Reschedule")) {
            onNodeWithText(verb).assertDoesNotExist()
        }
    }

    @Test
    fun markingAnEntryForwardsTheAction() = runComposeUiTest {
        val marks = mutableListOf<Pair<String, OccurrenceAction>>()
        render(agenda = listOf(item("e", ItemKind.Event)), onMark = { id, action -> marks += id to action })

        onNodeWithText("Done").performClick()
        assertEquals(listOf("e" to OccurrenceAction.Complete), marks)
    }

    @Test
    fun eventRowReschedulesViaAGridTap() = runComposeUiTest {
        val reschedules = mutableListOf<Pair<String, LocalDate>>()
        render(agenda = listOf(item("e", ItemKind.Event, title = "Standup")), onReschedule = { id, d -> reschedules += id to d })

        onNodeWithText("Reschedule").performClick()
        // The grid arms a "pick a new day" mode; the banner appears.
        onNodeWithText("Pick a new day", substring = true).assertIsDisplayed()
        // Tapping a day cell reschedules to that day.
        onNode(hasContentDescription("June 10, no items")).performClick()
        assertEquals(listOf("e" to LocalDate(2026, 6, 10)), reschedules)
    }

    @Test
    fun aPastUnfinishedFiringReadsScheduled_withNoShamingVocabulary() = runComposeUiTest {
        // A firing on a day now in the past, still Open: it must read plainly "Scheduled" (no overdue).
        // The status is exposed to a11y as a "Status: Scheduled" content description (clearAndSetSemantics).
        render(agenda = listOf(item("e", ItemKind.Event, title = "Standup", status = WorkingState.Open)))

        onNode(hasContentDescription("Status: Scheduled")).assertExists()
        for (banned in listOf("Overdue", "overdue", "Late", "Missed", "missed", "Failed", "behind")) {
            onNodeWithText(banned, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun anEmptyDayIsGentle() = runComposeUiTest {
        render(agenda = emptyList())
        onNodeWithText("Nothing on this day").assertIsDisplayed()
    }
}
