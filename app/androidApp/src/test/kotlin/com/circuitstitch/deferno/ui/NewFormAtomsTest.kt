package com.circuitstitch.deferno.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.shell.DictationStatus
import com.circuitstitch.deferno.shell.ui.NewDateField
import com.circuitstitch.deferno.shell.ui.NewDictationMessage
import com.circuitstitch.deferno.shell.ui.NewEventStartField
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct tests of the shared New-form atoms (#175, `:app:shell:ui` commonMain) on the existing
 * Robolectric/Compose harness — the pieces the [NewOverlayInteractionTest] flow does not reach:
 * the Dictation permission feedback (no Android test wires a speech engine) and the parse-or-clear
 * contract of the date/instant rows (so a half-typed value never POSTs an invalid `complete_by`).
 * The full form binding is exercised through the Android chrome by [NewOverlayInteractionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class NewFormAtomsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dictationMessage_permanentlyDenied_showsTheNote_andTheSettingsDeepLink() {
        var opened = 0
        composeRule.setContent {
            DefernoTheme {
                NewDictationMessage(
                    status = DictationStatus.PermissionPermanentlyDenied,
                    deniedNote = "Dictation needs microphone access. Tap the mic to allow it.",
                    permanentlyDeniedNote = "Dictation needs microphone access, which is turned off for this app.",
                    openSettingsLabel = "Open settings",
                    onOpenSettings = { opened++ },
                )
            }
        }

        // The gentle note renders (never a silent failure), with the OS-settings deep-link beside it.
        composeRule.onNodeWithContentDescription("Dictation status").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun dateField_pushesAParsedDate_andClearsAnUnparseableOne() {
        val pushed = mutableListOf<LocalDate?>()
        composeRule.setContent {
            DefernoTheme { NewDateField(value = null, onValueChange = { pushed += it }) }
        }

        composeRule.onNodeWithContentDescription("Date").performTextInput("2026-06-08")
        assertEquals(LocalDate(2026, 6, 8), pushed.last())

        // A now-unparseable value clears the date rather than leaving the stale parse behind.
        composeRule.onNodeWithContentDescription("Date").performTextInput("x")
        assertEquals(null, pushed.last())
    }

    @Test
    fun eventStartField_pushesAParsedInstant_andClearsAnUnparseableOne() {
        val pushed = mutableListOf<Instant?>()
        composeRule.setContent {
            DefernoTheme { NewEventStartField(value = null, onValueChange = { pushed += it }) }
        }

        composeRule.onNodeWithContentDescription("Event start").performTextInput("2026-06-08T09:00:00Z")
        assertEquals(Instant.parse("2026-06-08T09:00:00Z"), pushed.last())

        composeRule.onNodeWithContentDescription("Event start").performTextInput("x")
        assertEquals(null, pushed.last())
    }
}
