package com.circuitstitch.deferno.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.shell.BrainDumpContent
import com.circuitstitch.deferno.shell.BrainDumpState
import com.circuitstitch.deferno.shell.DraftCard
import com.circuitstitch.deferno.shell.DraftStatus
import com.circuitstitch.deferno.shell.FailureReason
import com.circuitstitch.deferno.shell.Phase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Brain dump body's render states (ADR-0027) on the Robolectric/Compose harness: the reviewable
 * draft cards and their accept affordance, the gentle "not set up" failure copy, and the live listening
 * transcript. The permission round-trip itself lives in [com.circuitstitch.deferno.shell.BrainDumpScreen]
 * and is exercised through the real shell elsewhere; this pins the stateless rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class BrainDumpContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun review_showsDraftCards_andAcceptFiresPerDraft() {
        var accepted: String? = null
        composeRule.setContent {
            DefernoTheme {
                BrainDumpContent(
                    state = BrainDumpState(
                        phase = Phase.Review,
                        drafts = listOf(
                            DraftCard(id = "d1", title = "Buy milk", detail = "Due 2026-06-15"),
                            DraftCard(id = "d2", title = "Call mom", detail = null),
                        ),
                    ),
                    onMic = {},
                    onAccept = { accepted = it },
                    onDismissDraft = {},
                    onClose = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Buy milk").assertIsDisplayed()
        composeRule.onNodeWithText("Call mom").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add task: Buy milk").performClick()
        org.junit.Assert.assertEquals("d1", accepted)
    }

    @Test
    fun failedNotConfigured_showsSetupGuidance() {
        composeRule.setContent {
            DefernoTheme {
                BrainDumpContent(
                    state = BrainDumpState(phase = Phase.Failed(FailureReason.NotConfigured)),
                    onMic = {},
                    onAccept = {},
                    onDismissDraft = {},
                    onClose = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("Set up the assistant in Settings → Agent to turn brain dumps into tasks.")
            .assertIsDisplayed()
    }

    @Test
    fun offlineDraft_offersRetry_whichReAccepts() {
        var accepted: String? = null
        composeRule.setContent {
            DefernoTheme {
                BrainDumpContent(
                    state = BrainDumpState(
                        phase = Phase.Review,
                        drafts = listOf(
                            DraftCard(id = "d1", title = "Buy milk", detail = null, status = DraftStatus.Offline),
                        ),
                    ),
                    onMic = {},
                    onAccept = { accepted = it },
                    onDismissDraft = {},
                    onClose = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("Retry").performClick()
        org.junit.Assert.assertEquals("d1", accepted)
    }

    @Test
    fun listening_showsTheLiveTranscript() {
        composeRule.setContent {
            DefernoTheme {
                BrainDumpContent(
                    state = BrainDumpState(phase = Phase.Listening, transcript = "buy milk and call mom"),
                    onMic = {},
                    onAccept = {},
                    onDismissDraft = {},
                    onClose = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("buy milk and call mom").assertIsDisplayed()
        composeRule.onNodeWithText("Stop & review").assertIsDisplayed()
    }
}
