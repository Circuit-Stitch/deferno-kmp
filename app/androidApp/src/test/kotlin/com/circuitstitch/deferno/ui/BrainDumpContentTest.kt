package com.circuitstitch.deferno.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.shell.BrainDumpContent
import com.circuitstitch.deferno.shell.BrainDumpState
import com.circuitstitch.deferno.shell.Phase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Brain dump body's render states (ADR-0027, Stage 4 async rework) on the Robolectric/Compose
 * harness: the simple recorder's idle → recording → enqueued lifecycle, its failure retry, and the
 * permission affordance. The permission round-trip itself lives in
 * [com.circuitstitch.deferno.shell.BrainDumpScreen] and is exercised through the real shell elsewhere;
 * this pins the stateless rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class BrainDumpContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idle_showsStartRecording_andMicFires() {
        var micFired = false
        composeRule.setContent {
            DefernoTheme {
                BrainDumpContent(
                    state = BrainDumpState(phase = Phase.Idle),
                    onMic = { micFired = true },
                    onClose = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("Start recording").assertExists()
        composeRule.onNodeWithText("Start recording").performClick()
        assertTrue(micFired)
    }

    @Test
    fun recording_showsStop_andStopFires() {
        var micFired = false
        composeRule.setContent {
            DefernoTheme {
                BrainDumpContent(
                    state = BrainDumpState(phase = Phase.Recording),
                    onMic = { micFired = true },
                    onClose = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("Recording…").assertExists()
        composeRule.onNodeWithText("Stop").assertExists()
        composeRule.onNodeWithText("Stop").performClick()
        assertTrue(micFired)
    }

    @Test
    fun enqueued_showsTranscribingNote_andDoneCloses() {
        var closed = false
        composeRule.setContent {
            DefernoTheme {
                BrainDumpContent(
                    state = BrainDumpState(phase = Phase.Enqueued),
                    onMic = {},
                    onClose = { closed = true },
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "Transcribing in the background — we'll let you know when your drafts are ready in the Inbox.",
        ).assertExists()
        composeRule.onNodeWithText("Done").performClick()
        assertTrue(closed)
    }

    @Test
    fun failed_offersRetry() {
        var micFired = false
        composeRule.setContent {
            DefernoTheme {
                BrainDumpContent(
                    state = BrainDumpState(phase = Phase.Failed),
                    onMic = { micFired = true },
                    onClose = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("Couldn't record that. Try again.").assertExists()
        composeRule.onNodeWithText("Try again").performClick()
        assertTrue(micFired)
    }

    @Test
    fun permanentlyDenied_offersOpenSettings() {
        var settingsOpened = false
        composeRule.setContent {
            DefernoTheme {
                BrainDumpContent(
                    state = BrainDumpState(phase = Phase.PermissionPermanentlyDenied),
                    onMic = {},
                    onClose = {},
                    onOpenSettings = { settingsOpened = true },
                )
            }
        }
        composeRule.onNodeWithText("Open settings").performClick()
        assertTrue(settingsOpened)
    }
}
