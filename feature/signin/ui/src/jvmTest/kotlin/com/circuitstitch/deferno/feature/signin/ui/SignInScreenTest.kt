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
 * The desktop render test for the sign-in screen (#15, cf. feature:profile:ui) — a Compose-Multiplatform
 * UI test on the JVM-fast path (no device) driving [SignInScreen] over a fake [SignInComponent]. It
 * covers the masked-field reveal toggle (ADR-0009), the submit-enablement gate, the inline error, and
 * the submit wiring. The sign-in state machine itself is unit-tested in feature:signin.
 */
@OptIn(ExperimentalTestApi::class)
class SignInScreenTest {

    @Test
    fun idle_showsTheTokenFieldAndADisabledSignInButton() = runComposeUiTest {
        setContent { Themed { SignInScreen(FakeSignInComponent(SignInState())) } }

        onNodeWithText("Personal access token").assertExists()
        // Blank token → the button is present but disabled.
        onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun aNonBlankToken_enablesSignIn_andClickingItSubmits() = runComposeUiTest {
        val fake = FakeSignInComponent(SignInState(token = "pat-123"))
        setContent { Themed { SignInScreen(fake) } }

        onNodeWithText("Sign in").assertIsEnabled()
        onNodeWithText("Sign in").performClick()
        assertEquals(1, fake.submitCount)
    }

    @Test
    fun revealToggle_defaultsToMasked_andRoundTrips() = runComposeUiTest {
        setContent { Themed { SignInScreen(FakeSignInComponent(SignInState(token = "secret"))) } }

        // Default is masked: the toggle offers "Show", not "Hide" (ADR-0009 — secret hidden by default).
        onNodeWithText("Hide").assertDoesNotExist()
        onNodeWithText("Show").performClick()
        onNodeWithText("Hide").assertExists()
        // Toggling back re-masks.
        onNodeWithText("Hide").performClick()
        onNodeWithText("Show").assertExists()
    }

    @Test
    fun invalidToken_rendersTheInlineError() = runComposeUiTest {
        val state = SignInState(token = "bad", error = SignInError.InvalidToken)
        setContent { Themed { SignInScreen(FakeSignInComponent(state)) } }

        onNodeWithText("That token", substring = true).assertExists()
    }

    @Test
    fun validating_showsTheInFlightLabel() = runComposeUiTest {
        val state = SignInState(token = "pat", isValidating = true)
        setContent { Themed { SignInScreen(FakeSignInComponent(state)) } }

        onNodeWithText("Signing in…").assertExists()
    }
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** A fixed-state [SignInComponent] double — records submit / token edits without a service or DI graph. */
private class FakeSignInComponent(initial: SignInState) : SignInComponent {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<SignInState> = _state

    var submitCount = 0
        private set

    override fun onTokenChange(token: String) {
        _state.value = _state.value.copy(token = token, error = null)
    }

    override fun onSubmit() {
        submitCount++
    }
}
