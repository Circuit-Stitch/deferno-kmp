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
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.OccurrenceAction
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

/**
 * Roborazzi screenshot baselines for the Calendar Destination (#74): the month grid (with occurrence
 * markers) above the selected day's agenda (its Occurrences + a dated item, with the kind-aware action
 * set), plus the gentle empty-day state — in the Deferno palette (light + dark). Drives a real
 * [DefaultCalendarComponent] over an in-memory [DemoCalendarRepository] on [Dispatchers.Unconfined]
 * (state resolves synchronously), with a **fixed** `today` so the baseline never drifts with the clock.
 *
 * Each agenda chip is now a *derived* occurrence-state reading, so the harness supplies all three of
 * its inputs (facts, coverage, definition state) beside the feed rows — see [SampleCalendar]. Fixing
 * `today` matters more than it used to: the Scheduled-vs-Missed split is a function of it, so a
 * clock-read here would make the baselines rot overnight rather than merely drift.
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

    private fun calendarComponent(): CalendarComponent =
        DefaultCalendarComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            calendarRepository = DemoCalendarRepository(SampleCalendar.markers, SampleCalendar.agenda),
            occurrenceEditor = NoopOccurrenceEditor,
            // The agenda chips are derived, so the baseline needs all three reading inputs, not just
            // the feed rows: without coverage every firing would capture as Unknown.
            occurrenceFacts = DemoOccurrenceFactStore(SampleCalendar.facts),
            occurrenceCoverage = DemoOccurrenceCoverageStore(SampleCalendar.coverage),
            definitionStates = DemoDefinitionStateSource(SampleCalendar.definitionStates),
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
}

/** No-op occurrence editor for the screenshot harness (the acts aren't exercised by a static capture). */
private val NoopOccurrenceEditor = object : OccurrenceEditor {
    override suspend fun mark(itemId: String, action: OccurrenceAction) {}
    override suspend fun clear(itemId: String) {}
    override suspend fun reschedule(itemId: String, newDate: LocalDate) {}
}
