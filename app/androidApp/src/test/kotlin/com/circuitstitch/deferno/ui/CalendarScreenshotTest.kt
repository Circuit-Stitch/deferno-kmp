package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.definition.DefinitionRef
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.demo.DemoCalendarRepository
import com.circuitstitch.deferno.demo.DemoDefinitionStateSource
import com.circuitstitch.deferno.demo.DemoOccurrenceCoverageStore
import com.circuitstitch.deferno.demo.DemoOccurrenceFactStore
import com.circuitstitch.deferno.demo.SampleCalendar
import com.circuitstitch.deferno.feature.calendar.CalendarComponent
import com.circuitstitch.deferno.feature.calendar.DefaultCalendarComponent
import com.circuitstitch.deferno.feature.calendar.OccurrenceEditor
import com.circuitstitch.deferno.feature.calendar.ui.CalendarScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Roborazzi screenshot baselines for the Calendar Destination (#74): the month grid (with occurrence
 * markers) above the selected day's agenda (its Occurrences + a dated item, with the kind-aware action
 * set), a **past** day whose firings read Missed and Not synced, plus the gentle empty-day state — in
 * the Deferno palette (light + dark). Drives a real [DefaultCalendarComponent] over an in-memory
 * [DemoCalendarRepository] on [Dispatchers.Unconfined] (state resolves synchronously), with a **fixed**
 * `today` so the baseline never drifts with the clock.
 *
 * Each agenda chip is now a *derived* occurrence-state reading, so the harness supplies all three of
 * its inputs (facts, coverage, definition state) beside the feed rows — see [SampleCalendar]. Fixing
 * `today` matters more than it used to: the Scheduled-vs-Missed split is a function of it, so a
 * clock-read here would make the baselines rot overnight rather than merely drift.
 *
 * Between them the four captures pin **six of the seven** [com.circuitstitch.deferno.core.model.OccurrenceState]
 * readings plus the non-firing fallback: Done on time / Skipped / Scheduled on [SampleCalendar.day]
 * (with a dated Task rendering its own `WorkingState`, the `occurrence == null` branch), and Missed /
 * Not synced on [PastDay]. In-progress and Done-late are the two the pixels do not cover; they are
 * pinned by the View tests in `:feature:calendar:ui`, which can assert a label without a golden.
 *
 * Record with `./gradlew :app:androidApp:recordRoborazziStagingDebug` (baselines are flavor-agnostic —
 * record once), compare with `verifyRoborazziStagingDebug`. **Neither is on the `check` path or in CI**,
 * so only a deliberate local run catches a drifted golden. With no Roborazzi mode set, `captureRoboImage`
 * is a no-op, so these also run harmlessly as part of the normal unit-test task.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class CalendarScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The harness component. The four reading inputs are parameters rather than constants so a capture
     * can add rows of its own without reshaping [SampleCalendar] — the fixture the View tests and the
     * two month baselines are calibrated against. [PastDay] is layered on top by exactly this route.
     */
    private fun calendarComponent(
        agenda: Map<LocalDate, List<CalendarItem>> = SampleCalendar.agenda,
        facts: List<OccurrenceFact> = SampleCalendar.facts,
        coverage: List<OccurrenceCoverage> = SampleCalendar.coverage,
        definitionStates: Map<DefinitionRef, DefinitionState> = SampleCalendar.definitionStates,
    ): CalendarComponent =
        DefaultCalendarComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            calendarRepository = DemoCalendarRepository(SampleCalendar.markers, agenda),
            occurrenceEditor = NoopOccurrenceEditor,
            // The agenda chips are derived, so the baseline needs all three reading inputs, not just
            // the feed rows: without coverage every firing would capture as Unknown.
            occurrenceFacts = DemoOccurrenceFactStore(facts),
            occurrenceCoverage = DemoOccurrenceCoverageStore(coverage),
            definitionStates = DemoDefinitionStateSource(definitionStates),
            today = { SampleCalendar.day },
            tz = "UTC",
            output = {},
            coroutineContext = Dispatchers.Unconfined,
        )

    private fun capture(name: String, darkTheme: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp")
    fun month_withMarkersAndAgenda_light() =
        capture("calendar_month_light") { CalendarScreen(calendarComponent()) }

    @Test
    @Config(qualifiers = "w400dp-h1000dp")
    fun month_withMarkersAndAgenda_dark() =
        capture("calendar_month_dark", darkTheme = true) { CalendarScreen(calendarComponent()) }

    @Test
    @Config(qualifiers = "w400dp-h1000dp")
    fun emptyDay_isGentle_light() = capture("calendar_empty_day_light") {
        // Select a day with no agenda — the gentle "Nothing on this day" state (design-principle #4).
        CalendarScreen(calendarComponent().also { it.onDaySelected(LocalDate(2026, 6, 17)) })
    }

    /**
     * A day that has already closed — the two readings that only a *past* date can produce, side by
     * side. Neither is reachable from [SampleCalendar.day], because that day **is** `today` and an
     * unresolved firing on it is honestly Scheduled.
     *
     * This capture exists because those two are the readings the whole slice was written for, and they
     * are the two most likely to regress into something wrong-but-plausible: **Missed** must stay muted
     * (an error tone would restore the reproach ADR-0053 decision 7 removes), and **Not synced** must
     * stay a bare label with no container — if it ever paints like the Scheduled chip, the surface is
     * back to guessing at days it has never looked at.
     */
    @Test
    @Config(qualifiers = "w400dp-h1000dp")
    fun pastDay_missedAndNotSynced_light() = capture("calendar_past_day_light") {
        CalendarScreen(
            calendarComponent(
                agenda = SampleCalendar.agenda + PastDay.agenda,
                coverage = SampleCalendar.coverage + PastDay.coverage,
                definitionStates = SampleCalendar.definitionStates + PastDay.definitionStates,
            ).also { it.onDaySelected(PastDay.day) },
        )
    }
}

/**
 * A second sample day, **before** [SampleCalendar.day], carrying the two readings that are a function
 * of the past. Kept here rather than in `SampleCalendar` so the fixture the month baselines and the
 * `:feature:calendar:ui` View tests are calibrated against stays exactly as wave 2 shaped it.
 *
 * Both rows are unresolved — there is no [OccurrenceFact] for either — so each one's chip is decided
 * entirely by whether this device ever synced that date:
 * - **Evening walk** is inside [coverage] on an Active definition, so the absence of a record is
 *   evidence: the firing came due and nothing happened. **Missed**.
 * - **Take out the bins** is deliberately *outside* coverage — no range is recorded for it at all — so
 *   the same absence says nothing whatsoever. **Not synced**, never Missed. Coverage is the only thing
 *   separating these two rows; everything else about them is identical, which is the point.
 *
 * The day is 8 June because [SampleCalendar.markers] already puts two marker dots there, so the grid
 * above the agenda agrees with the agenda below it.
 */
private object PastDay {
    val day: LocalDate = LocalDate(2026, 6, 8)

    private fun item(id: String, kind: ItemKind, title: String) = CalendarItem(
        id = id,
        // The definition id the facts and coverage are keyed on — `taskId`, not `seriesId` (#380).
        taskId = "task-$id",
        seriesId = "$id-series",
        title = title,
        date = day,
        start = Instant.parse("2026-06-08T08:00:00Z"),
        end = Instant.parse("2026-06-08T08:30:00Z"),
        allDay = false,
        // Deliberately Open on both rows: a firing's own WorkingState no longer reaches the chip, and
        // leaving it at the neutral default proves it — if either row ever captured as "Scheduled",
        // the View has fallen back to reading `item.status` for a firing.
        status = WorkingState.Open,
        kind = kind,
        source = CalendarSource.Deferno,
    )

    val agenda: Map<LocalDate, List<CalendarItem>> = mapOf(
        day to listOf(
            item("h2", ItemKind.Habit, "Evening walk"),
            item("c2", ItemKind.Chore, "Take out the bins"),
        ),
    )

    /** Only the Habit is synced. The Chore's absence from this list is what makes it Not synced. */
    val coverage: List<OccurrenceCoverage> = listOf(
        OccurrenceCoverage(ItemKind.Habit, "task-h2", day, day),
    )

    val definitionStates: Map<DefinitionRef, DefinitionState> = mapOf(
        // Active is load-bearing for the Missed row: a shelved definition's past empty days read
        // Skipped, because they are history rather than a reproach (OccurrenceStateResolver).
        DefinitionRef(ItemKind.Habit, "task-h2") to DefinitionState.Active,
        DefinitionRef(ItemKind.Chore, "task-c2") to DefinitionState.Active,
    )
}

/** No-op occurrence editor for the screenshot harness (the acts aren't exercised by a static capture). */
private val NoopOccurrenceEditor = object : OccurrenceEditor {
    override suspend fun mark(itemId: String, action: OccurrenceAction) {}
    override suspend fun clear(itemId: String) {}
    override suspend fun reschedule(itemId: String, newDate: LocalDate) {}
}
