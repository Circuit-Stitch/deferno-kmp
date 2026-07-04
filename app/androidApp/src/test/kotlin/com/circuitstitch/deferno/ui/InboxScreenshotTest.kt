package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftId
import com.circuitstitch.deferno.feature.braindumps.InboxNote
import com.circuitstitch.deferno.feature.braindumps.InboxRow
import com.circuitstitch.deferno.feature.braindumps.InboxState
import com.circuitstitch.deferno.feature.braindumps.ui.InboxScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * Roborazzi screenshot baselines (ADR-0015 Inbox amendment) for the Inbox Destination: the triage queue
 * of Brain dump draft cards (one with a due line + notes, one with an offline note, one mid-accept), and
 * the calm empty state. Record with `./gradlew :app:androidApp:recordRoborazziDebug`; with no Roborazzi
 * mode set `captureRoboImage` is a no-op, so these also run as part of the normal unit-test task.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class InboxScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, darkTheme: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val sampleRows = listOf(
        InboxRow(
            BrainDumpDraft(
                id = BrainDumpDraftId("1"),
                title = "Email the venue about parking",
                notes = "Confirm we have 20 spots reserved for Saturday.",
                completeBy = LocalDate(2026, 6, 16),
                createdAt = Instant.parse("2026-06-14T09:00:00Z"),
            ),
        ),
        InboxRow(
            BrainDumpDraft(
                id = BrainDumpDraftId("2"),
                title = "Draft the retro notes",
                createdAt = Instant.parse("2026-06-14T09:01:00Z"),
            ),
            noteKind = InboxNote.Offline,
        ),
        InboxRow(
            BrainDumpDraft(
                id = BrainDumpDraftId("3"),
                title = "Book the dentist",
                completeBy = LocalDate(2026, 6, 20),
                deadlineTimeOfDay = LocalTime(14, 30),
                createdAt = Instant.parse("2026-06-14T09:02:00Z"),
            ),
            accepting = true,
        ),
    )

    @Test
    fun inbox_populated_light() = capture("inbox_populated_light") {
        InboxScreen(FakeInboxComponent(InboxState(rows = sampleRows)))
    }

    @Test
    fun inbox_populated_dark() = capture("inbox_populated_dark", darkTheme = true) {
        InboxScreen(FakeInboxComponent(InboxState(rows = sampleRows)))
    }

    @Test
    fun inbox_empty_light() = capture("inbox_empty_light") {
        InboxScreen(FakeInboxComponent(InboxState()))
    }
}
