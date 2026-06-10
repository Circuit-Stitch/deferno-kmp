package com.circuitstitch.deferno.feature.signin

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.auth.SignInResult
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DefaultSignInComponent] (#15, ADR-0012/0026): drives the system-browser OAuth flow and the dev paste
 * fallback through the [com.circuitstitch.deferno.core.data.auth.SignInService], mapping each outcome to
 * the observable [SignInState] the View renders — busy, then either gone (success → the shell swaps the
 * surface), an inline [SignInError], or (browser cancel) silently back to idle. JVM-fast path (ADR-0006),
 * `StandardTestDispatcher`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSignInComponentTest {

    private val account = Account(AccountId("u-1"), "Ada")

    private fun TestScope.component(service: FakeSignInService) = DefaultSignInComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        signInService = service,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun startsIdleWithNoTokenEntryShown() = runTest {
        val component = component(FakeSignInService(SignInResult.Unavailable))

        assertEquals(SignInState(), component.state.value)
        assertTrue(component.state.value.canStartBrowser)
        assertFalse(component.state.value.showTokenEntry)
        assertFalse(component.state.value.canSubmitToken)
    }

    // --- browser OAuth path (primary) ---

    @Test
    fun signInClick_showsBusyThenSettlesWithoutError() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = FakeSignInService(SignInResult.Success(account)).apply { this.gate = gate }
        val component = component(service)

        component.onSignInClick()
        advanceUntilIdle() // runs up to the gate

        assertTrue(component.state.value.isBusy)
        assertEquals(1, service.browserCalls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(component.state.value.isBusy)
        assertNull(component.state.value.error)
    }

    @Test
    fun signInClick_cancelled_returnsToIdleWithoutError() = runTest {
        val component = component(FakeSignInService(SignInResult.Cancelled))

        component.onSignInClick()
        advanceUntilIdle()

        assertFalse(component.state.value.isBusy)
        assertNull(component.state.value.error)
    }

    @Test
    fun signInClick_unavailable_surfacesTheUnavailableError() = runTest {
        val component = component(FakeSignInService(SignInResult.Unavailable))

        component.onSignInClick()
        advanceUntilIdle()

        assertEquals(SignInError.Unavailable, component.state.value.error)
        assertFalse(component.state.value.isBusy)
    }

    @Test
    fun twoRapidSignInClicks_startTheBrowserOnce() = runTest {
        val service = FakeSignInService(SignInResult.Success(account))
        val component = component(service)

        component.onSignInClick()
        component.onSignInClick()
        advanceUntilIdle()

        assertEquals(1, service.browserCalls)
    }

    // --- dev paste fallback ---

    @Test
    fun useTokenInstead_revealsTheTokenEntryField() = runTest {
        val component = component(FakeSignInService(SignInResult.Unavailable))

        component.onUseTokenInstead()

        assertTrue(component.state.value.showTokenEntry)
    }

    @Test
    fun editingTheTokenUpdatesItAndEnablesSubmit() = runTest {
        val component = component(FakeSignInService(SignInResult.Unavailable))

        component.onTokenChange("pat-123")

        assertEquals("pat-123", component.state.value.token)
        assertTrue(component.state.value.canSubmitToken)
    }

    @Test
    fun submitWithABlankTokenIsANoOp() = runTest {
        val service = FakeSignInService(SignInResult.Success(account))
        val component = component(service)

        component.onTokenChange("   ")
        component.onSubmit()
        advanceUntilIdle()

        assertTrue(service.tokens.isEmpty())
    }

    @Test
    fun twoRapidSubmits_validateOnlyOnce() = runTest {
        // Two taps in the same frame: the synchronous in-flight guard drops the second.
        val service = FakeSignInService(SignInResult.Success(account))
        val component = component(service)

        component.onTokenChange("pat")
        component.onSubmit()
        component.onSubmit()
        advanceUntilIdle()

        assertEquals(listOf("pat"), service.tokens)
    }

    @Test
    fun aValidPastedToken_showsBusyThenSettlesWithoutError_andTrimsTheToken() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = FakeSignInService(SignInResult.Success(account)).apply { this.gate = gate }
        val component = component(service)

        component.onTokenChange("  pat-xyz  ")
        component.onSubmit()
        advanceUntilIdle()

        assertTrue(component.state.value.isBusy)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(component.state.value.isBusy)
        assertNull(component.state.value.error)
        // The token was trimmed before it reached the service (ADR-0023).
        assertEquals(listOf("pat-xyz"), service.tokens)
    }

    @Test
    fun anInvalidPastedToken_surfacesTheInvalidTokenError() = runTest {
        val component = component(FakeSignInService(SignInResult.InvalidToken))

        component.onTokenChange("bad")
        component.onSubmit()
        advanceUntilIdle()

        assertEquals(SignInError.InvalidToken, component.state.value.error)
        assertFalse(component.state.value.isBusy)
    }

    @Test
    fun aTransientPasteFailure_surfacesTheUnavailableError() = runTest {
        val component = component(FakeSignInService(SignInResult.Unavailable))

        component.onTokenChange("pat")
        component.onSubmit()
        advanceUntilIdle()

        assertEquals(SignInError.Unavailable, component.state.value.error)
    }

    @Test
    fun editingTheTokenAfterAnError_clearsTheError() = runTest {
        val component = component(FakeSignInService(SignInResult.InvalidToken))
        component.onTokenChange("bad")
        component.onSubmit()
        advanceUntilIdle()
        assertEquals(SignInError.InvalidToken, component.state.value.error)

        component.onTokenChange("bad-fixed")

        assertNull(component.state.value.error)
    }
}
