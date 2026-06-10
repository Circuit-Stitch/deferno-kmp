package com.circuitstitch.deferno.feature.signin.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.feature.signin.SignInComponent
import com.circuitstitch.deferno.feature.signin.SignInError
import com.circuitstitch.deferno.feature.signin.SignInState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The desktop render test for the sign-in screen (#15, ADR-0012/0026) — a Compose-Multiplatform UI test
 * on the JVM-fast path (no device) driving [SignInScreen] over a fake [SignInComponent]. It covers the
 * browser-first primary button + wiring, the in-flight label, the inline browser error, and — behind the
 * developer flag — the paste fallback's reveal toggle (ADR-0009) and submit gate. The sign-in state
 * machine itself is unit-tested in feature:signin.
 */
@OptIn(ExperimentalTestApi::class)
class SignInScreenTest {

    @Test
    fun idle_showsTheBrowserSignInButton_andNoCredentialField() = runComposeUiTest {
        setContent { Themed { SignInScreen(FakeSignInComponent(SignInState())) } }

        onNodeWithText("Sign in").assertIsEnabled()
        // No in-app credential field by default (ADR-0012/0026): password lives in the browser.
        onNodeWithText("Personal access token").assertDoesNotExist()
    }

    @Test
    fun clickingSignIn_startsTheBrowserFlow() = runComposeUiTest {
        val fake = FakeSignInComponent(SignInState())
        setContent { Themed { SignInScreen(fake) } }

        onNodeWithText("Sign in").performClick()
        assertEquals(1, fake.signInClicks)
    }

    @Test
    fun busy_showsTheInFlightLabel() = runComposeUiTest {
        setContent { Themed { SignInScreen(FakeSignInComponent(SignInState(isBusy = true))) } }

        onNodeWithText("Signing in…").assertExists()
    }

    @Test
    fun unavailableError_rendersInline() = runComposeUiTest {
        val state = SignInState(error = SignInError.Unavailable)
        setContent { Themed { SignInScreen(FakeSignInComponent(state)) } }

        onNodeWithText("Couldn’t reach Deferno", substring = true).assertExists()
    }

    // --- developer paste fallback (only when showDeveloperOptions) ---

    @Test
    fun developerOptionsHidden_doesNotOfferTheTokenAffordance() = runComposeUiTest {
        setContent { Themed { SignInScreen(FakeSignInComponent(SignInState()), showDeveloperOptions = false) } }

        onNodeWithText("Use a token instead").assertDoesNotExist()
    }

    @Test
    fun useTokenInstead_isOfferedAndWired_whenDeveloperOptionsShown() = runComposeUiTest {
        val fake = FakeSignInComponent(SignInState())
        setContent { Themed { SignInScreen(fake, showDeveloperOptions = true) } }

        onNodeWithText("Use a token instead").performClick()
        assertEquals(1, fake.useTokenCount)
    }

    @Test
    fun tokenEntry_revealsFieldAndSubmits() = runComposeUiTest {
        val fake = FakeSignInComponent(SignInState(showTokenEntry = true, token = "pat-123"))
        setContent { Themed { SignInScreen(fake, showDeveloperOptions = true) } }

        onNodeWithText("Personal access token").assertExists()
        onNodeWithText("Sign in with token").assertIsEnabled()
        onNodeWithText("Sign in with token").performClick()
        assertEquals(1, fake.submitCount)
    }

    @Test
    fun tokenEntry_blankToken_disablesSubmit() = runComposeUiTest {
        setContent { Themed { SignInScreen(FakeSignInComponent(SignInState(showTokenEntry = true)), showDeveloperOptions = true) } }

        onNodeWithText("Sign in with token").assertIsNotEnabled()
    }

    @Test
    fun tokenEntry_revealToggle_defaultsToMasked_andRoundTrips() = runComposeUiTest {
        val state = SignInState(showTokenEntry = true, token = "secret")
        setContent { Themed { SignInScreen(FakeSignInComponent(state), showDeveloperOptions = true) } }

        // Masked by default: the toggle offers "Show", not "Hide" (ADR-0009).
        onNodeWithText("Hide").assertDoesNotExist()
        onNodeWithText("Show").performClick()
        onNodeWithText("Hide").assertExists()
        onNodeWithText("Hide").performClick()
        onNodeWithText("Show").assertExists()
    }

    @Test
    fun tokenEntry_invalidToken_rendersTheInlineError() = runComposeUiTest {
        val state = SignInState(showTokenEntry = true, token = "bad", error = SignInError.InvalidToken)
        setContent { Themed { SignInScreen(FakeSignInComponent(state), showDeveloperOptions = true) } }

        onNodeWithText("That token", substring = true).assertExists()
    }
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** A fixed-state [SignInComponent] double — records actions without a service or DI graph. */
private class FakeSignInComponent(initial: SignInState) : SignInComponent {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<SignInState> = _state

    var signInClicks = 0
        private set
    var useTokenCount = 0
        private set
    var submitCount = 0
        private set

    override fun onSignInClick() {
        signInClicks++
    }

    override fun onUseTokenInstead() {
        useTokenCount++
        _state.value = _state.value.copy(showTokenEntry = true)
    }

    override fun onTokenChange(token: String) {
        _state.value = _state.value.copy(token = token, error = null)
    }

    override fun onSubmit() {
        submitCount++
    }
}
