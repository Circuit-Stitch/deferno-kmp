package com.circuitstitch.deferno.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.feature.profile.ProfileState
import com.circuitstitch.deferno.feature.profile.ui.ProfileScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI interaction tests (#70) for the Profile screen, run on the JVM via Robolectric. They guard
 * the **destructive sign-out path** — it must be gated behind a confirm dialog (the button does NOT sign
 * out directly), confirming forwards the intent, and Cancel does not — plus the identity render and the
 * re-auth / unavailable retry intents. The [ProfileState] mapping itself is tested in `:feature:profile`
 * commonTest ([com.circuitstitch.deferno.feature.profile.ProfileComponentTest]).
 */
@RunWith(RobolectricTestRunner::class)
class ProfileScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent { DefernoTheme { content() } }
    }

    @Test
    fun signedIn_rendersTheIdentityCardAndAccountControls() {
        setContent { ProfileScreen(FakeProfileComponent(ProfileState.SignedIn(sampleUser))) }

        composeRule.onNodeWithText("Sample User").assertIsDisplayed()
        composeRule.onNodeWithText("@sampleuser").assertIsDisplayed()
        composeRule.onNodeWithText("u-e4h2qk").assertIsDisplayed() // the personal Org chip
        composeRule.onNodeWithText("Work").assertIsDisplayed()     // the active Account label
    }

    @Test
    fun signOut_isGatedByAConfirmDialog_andDoesNotSignOutOnOpen() {
        val component = FakeProfileComponent(ProfileState.SignedIn(sampleUser))
        setContent { ProfileScreen(component) }

        composeRule.onNodeWithText("Sign out").performClick()

        composeRule.onNodeWithText("Sign out of Work?").assertIsDisplayed()
        assertEquals("opening the confirm dialog must not sign out", 0, component.signOutCount)
    }

    @Test
    fun confirmingTheDialog_forwardsTheSignOutIntent() {
        val component = FakeProfileComponent(ProfileState.SignedIn(sampleUser))
        setContent { ProfileScreen(component) }

        composeRule.onNodeWithText("Sign out").performClick() // opens the dialog (one match)
        // The dialog's confirm button is the second "Sign out" (the trigger stays composed behind it).
        composeRule.onAllNodesWithText("Sign out").onLast().performClick()

        assertEquals(1, component.signOutCount)
    }

    @Test
    fun cancellingTheDialog_doesNotSignOut() {
        val component = FakeProfileComponent(ProfileState.SignedIn(sampleUser))
        setContent { ProfileScreen(component) }

        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Sign out of Work?").assertDoesNotExist()
        assertEquals(0, component.signOutCount)
    }

    @Test
    fun reauthRequired_promptsSignInAgain_andForwardsRetry() {
        val component = FakeProfileComponent(ProfileState.ReauthRequired)
        setContent { ProfileScreen(component) }

        composeRule.onNodeWithText("Session expired").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in again").performClick()

        assertEquals(1, component.retryCount)
    }

    @Test
    fun unavailable_offersRetry_andForwardsTheIntent() {
        val component = FakeProfileComponent(ProfileState.Unavailable)
        setContent { ProfileScreen(component) }

        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(1, component.retryCount)
    }
}
