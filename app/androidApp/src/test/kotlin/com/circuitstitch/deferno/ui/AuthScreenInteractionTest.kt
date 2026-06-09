package com.circuitstitch.deferno.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.feature.auth.AuthState
import com.circuitstitch.deferno.feature.auth.ui.AuthScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI interaction tests (#20) for the tracer's minimal `/auth/me` screen, run on the JVM via
 * Robolectric. They prove the thin View renders the signed-in identity (the acceptance criterion:
 * "username / display name rendered on a minimal screen") and forwards the retry intent for the
 * re-auth / unavailable states — the state-mapping logic itself is tested in `:feature:auth`
 * commonTest ([com.circuitstitch.deferno.feature.auth.AuthComponentTest]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent { DefernoTheme { content() } }
    }

    @Test
    fun signedIn_rendersDisplayNameAndUsername() {
        setContent { AuthScreen(FakeAuthComponent(AuthState.SignedIn(sampleUser))) }

        composeRule.onNodeWithText("Sample User").assertIsDisplayed()
        composeRule.onNodeWithText("@sampleuser").assertIsDisplayed()
    }

    @Test
    fun reauthRequired_promptsSignInAgain_andForwardsTheIntent() {
        val component = FakeAuthComponent(AuthState.ReauthRequired)
        setContent { AuthScreen(component) }

        composeRule.onNodeWithText("Session expired").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in again").performClick()

        assertEquals(1, component.retryCount)
    }

    @Test
    fun unavailable_offersRetry_andForwardsTheIntent() {
        val component = FakeAuthComponent(AuthState.Unavailable)
        setContent { AuthScreen(component) }

        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(1, component.retryCount)
    }

    @Test
    fun loading_showsProgressCopy() {
        setContent { AuthScreen(FakeAuthComponent(AuthState.Loading)) }

        composeRule.onNodeWithText("Signing in…").assertIsDisplayed()
    }
}
