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
import com.circuitstitch.deferno.core.model.CalendarFiring
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.covers
import com.circuitstitch.deferno.core.model.resolveOccurrenceState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The Calendar View render test (#74) — a Compose-Multiplatform UI test on the JVM-fast path (no
 * device). It drives the stateless [CalendarContent] with fixed inputs + intent spies, covering: the
 * month grid (label + day-tap selection), the day agenda's **kind-aware** action set (a habit is binary
 * — no start/skip; every recurring kind can be rescheduled since #380), the grid-tap reschedule, the
 * chip's reading of each [OccurrenceState], and — crucially — the register those readings are spoken
 * in. The component logic is unit-tested in feature:calendar.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarContentTest {

    private val june = LocalDate(2026, 6, 1)

    /**
     * A rendered agenda row: the feed row plus how its firing went, which is what the View is handed
     * (ADR-0053 decision 4). The old factory took a [WorkingState] and the chip read it — the exact
     * defect this slice removes, since on a firing that value is the *definition's* progress and an
     * offline mark no longer touches it at all.
     *
     * [occurrence] defaults to [OccurrenceState.Scheduled] (the ordinary "hasn't come due" firing);
     * pass `null` for a row that is not a firing at all — a dated Task, an unresolved-kind row, a
     * synced external event — which is the only case where [status] is still read.
     */
    private fun item(
        id: String,
        kind: ItemKind?,
        seriesId: String? = "series-$id",
        title: String = "Entry $id",
        occurrence: OccurrenceState? = OccurrenceState.Scheduled,
        status: WorkingState = WorkingState.Open,
        date: LocalDate = june,
        source: CalendarSource = CalendarSource.Deferno,
    ) = CalendarFiring(
        item = CalendarItem(
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
        ),
        occurrence = occurrence,
    )

    private fun ComposeUiTest.render(
        selectedDay: LocalDate = june,
        markers: Map<LocalDate, Int> = emptyMap(),
        agenda: List<CalendarFiring> = emptyList(),
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
        // Neither is an actionable firing, so neither carries an occurrence reading — they fall back to
        // their own WorkingState, which for a non-firing row is a genuine fact about that item.
        render(
            agenda = listOf(
                item("g", ItemKind.Event, title = "Synced meeting", source = CalendarSource.External, occurrence = null),
                item("u", kind = null, title = "Unknown series", occurrence = null),
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
    fun aPastUnresolvedFiring_readsMissedInsideCoverageAndNotSyncedOutsideIt() = runComposeUiTest {
        // The state half of the old `aPastUnfinishedFiringReadsScheduled` test. That test asserted a
        // premise it never actually modelled: there was no `today` in this harness at all, so a row
        // dated `june` with status Open was "past" only in a comment. Here the relationship is real —
        // both rows are dated June 1 and today is June 10 — and it is the relationship, not a
        // hand-picked enum, that produces the two readings.
        //
        // The readings come from the shipping resolver rather than being asserted into place, so this
        // pins the whole chain the user actually sees: a past date + no recorded resolution + an Active
        // definition renders one way inside synced coverage and the other way outside it.
        val today = LocalDate(2026, 6, 10)
        val coverage = listOf(
            OccurrenceCoverage(ItemKind.Chore, "task-c", LocalDate(2026, 5, 1), LocalDate(2026, 6, 30)),
        )
        fun readingFor(taskId: String) = resolveOccurrenceState(
            // Neither firing has a recorded resolution. Inside coverage that absence is evidence
            // (nothing was logged, and the chore is still live) → Missed. Outside it the absence is
            // only this device's ignorance → Unknown, which the chip must say out loud.
            fact = null,
            covered = coverage.covers(ItemKind.Chore, taskId, june),
            definitionState = DefinitionState.Active,
            date = june,
            today = today,
        )

        render(
            agenda = listOf(
                item("c", ItemKind.Chore, title = "Dishes", occurrence = readingFor("task-c")),
                item("n", ItemKind.Chore, title = "Water plants", occurrence = readingFor("task-n")),
            ),
        )

        // The chip is exposed to a11y as a "Status: …" content description (clearAndSetSemantics).
        onNode(hasContentDescription("Status: Missed")).assertExists()
        // Not the Scheduled dash and not an error: an unsynced day states the device fact instead of
        // guessing at one (ADR-0053 decision 4).
        onNode(hasContentDescription("Status: Not synced")).assertExists()
    }

    @Test
    fun theRegisterStaysFlat_evenWithAMissedFiringOnScreen() = runComposeUiTest {
        // The vocabulary half of the old `aPastUnfinishedFiringReadsScheduled` test, and the one that
        // had to survive the state half inverting. Gentleness is vocabulary, not suppression (ADR-0053
        // decision 7): the surface now names Missed and Done-late plainly, and the guard is that it
        // still refuses the shaming register around them. "Missed"/"late" left the banned list because
        // they are now the catalog's own flat words; "overdue", "failed", "behind", a second-person
        // accusation and an exclamation mark did not, and never will.
        render(
            agenda = listOf(
                item("m", ItemKind.Chore, title = "Dishes", occurrence = OccurrenceState.Missed),
                item("l", ItemKind.Chore, title = "Bins", occurrence = OccurrenceState.DoneLate),
                item("n", ItemKind.Chore, title = "Water plants", occurrence = OccurrenceState.Unknown),
            ),
        )

        onNode(hasContentDescription("Status: Missed")).assertExists()
        onNode(hasContentDescription("Status: Done late")).assertExists()
        for (banned in listOf("Overdue", "overdue", "Failed", "failed", "behind", "Late!", "late!", "You missed", "you missed", "!")) {
            onNodeWithText(banned, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun aNonFiringRowStillReadsItsOwnWorkingState() = runComposeUiTest {
        // The null on CalendarFiring.occurrence means "not a firing", NOT "unknown". A synced external
        // event has no occurrence axis at all, so its own WorkingState is the honest reading — and it
        // must not be confused with the Not-synced chip, which is a claim about this device.
        render(
            agenda = listOf(
                item("g", ItemKind.Event, title = "Synced meeting", source = CalendarSource.External, occurrence = null, status = WorkingState.Done),
            ),
        )

        onNode(hasContentDescription("Status: Done")).assertExists()
        onNode(hasContentDescription("Status: Not synced")).assertDoesNotExist()
    }

    @Test
    fun everyOccurrenceStateHasItsOwnWord() {
        // The whole enum, asserted against the enum itself rather than against a hand-copied list: a
        // member added without a catalog word fails here instead of shipping a chip that reads as some
        // other state. That guard matters more than it looks — the Apple twin of this `when` is an
        // if-chain with a silent catch-all (a Kotlin enum bridges to Swift as an Objective-C class), so
        // this is the only place in the codebase where exhaustiveness is actually enforceable.
        val expected = mapOf(
            OccurrenceState.Scheduled to "Scheduled",
            OccurrenceState.InProgress to "In progress",
            OccurrenceState.DoneOnTime to "Done on time",
            OccurrenceState.DoneLate to "Done late",
            OccurrenceState.Skipped to "Skipped",
            OccurrenceState.Missed to "Missed",
            OccurrenceState.Unknown to "Not synced",
        )
        assertEquals(OccurrenceState.entries.toSet(), expected.keys, "every OccurrenceState needs a word")

        // One row per run: a LazyColumn only composes what fits, so seven rows at once would assert
        // nothing about the ones below the fold.
        expected.forEach { (state, word) ->
            runComposeUiTest {
                render(agenda = listOf(item("x", ItemKind.Chore, title = "Dishes", occurrence = state)))
                onNode(hasContentDescription("Status: $word")).assertExists()
            }
        }
    }

    @Test
    fun everyWorkingStateStillHasItsWordOnANonFiringRow() {
        // The fallback axis, kept whole: a dated Task or a synced external row has no occurrence, and
        // its own lifecycle progress is the honest reading. Open still says "Scheduled" here and is
        // still deliberately neutral — it is simply no longer the catch-all for a past unresolved day.
        val expected = mapOf(
            WorkingState.Open to "Scheduled",
            WorkingState.InProgress to "In progress",
            WorkingState.InReview to "In review",
            WorkingState.Done to "Done",
            WorkingState.Dropped to "Skipped",
        )
        assertEquals(WorkingState.entries.toSet(), expected.keys, "every WorkingState needs a word")

        expected.forEach { (state, word) ->
            runComposeUiTest {
                render(agenda = listOf(item("x", kind = null, title = "Dentist", occurrence = null, status = state)))
                onNode(hasContentDescription("Status: $word")).assertExists()
            }
        }
    }

    @Test
    fun anEmptyDayIsGentle() = runComposeUiTest {
        render(agenda = emptyList())
        onNodeWithText("Nothing on this day").assertIsDisplayed()
    }
}
