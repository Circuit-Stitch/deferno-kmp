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
 * [DefaultSignInComponent] (#15, ADR-0023): edits the token, validates it through the
 * [com.circuitstitch.deferno.core.data.auth.SignInService], and maps the outcome to the observable
 * [SignInState] the View renders — in-flight, then either gone (success → the shell swaps the surface)
 * or an inline [SignInError]. Driven on the JVM-fast path (ADR-0006) with a `StandardTestDispatcher`.
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
    fun startsIdleWithAnEmptyToken() = runTest {
        val component = component(FakeSignInService(SignInResult.Unavailable))

        assertEquals(SignInState(), component.state.value)
        assertFalse(component.state.value.canSubmit)
    }

    @Test
    fun editingTheTokenUpdatesItAndEnablesSubmit() = runTest {
        val component = component(FakeSignInService(SignInResult.Unavailable))

        component.onTokenChange("pat-123")

        assertEquals("pat-123", component.state.value.token)
        assertTrue(component.state.value.canSubmit)
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
        // Two taps in the same frame (before the first validation's launch body runs): the synchronous
        // in-flight guard must drop the second, so only one /auth/me + one addAccount fire.
        val service = FakeSignInService(SignInResult.Success(account))
        val component = component(service)

        component.onTokenChange("pat")
        component.onSubmit()
        component.onSubmit()
        advanceUntilIdle()

        assertEquals(listOf("pat"), service.tokens)
    }

    @Test
    fun aValidToken_showsValidatingThenSettlesWithoutError_andTrimsTheToken() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = FakeSignInService(SignInResult.Success(account)).apply { this.gate = gate }
        val component = component(service)

        component.onTokenChange("  pat-xyz  ")
        component.onSubmit()
        advanceUntilIdle() // runs up to the gate's suspension point

        assertTrue(component.state.value.isValidating)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(component.state.value.isValidating)
        assertNull(component.state.value.error)
        // The token was trimmed before it reached the service (ADR-0023).
        assertEquals(listOf("pat-xyz"), service.tokens)
    }

    @Test
    fun anInvalidToken_surfacesTheInvalidTokenError() = runTest {
        val component = component(FakeSignInService(SignInResult.InvalidToken))

        component.onTokenChange("bad")
        component.onSubmit()
        advanceUntilIdle()

        assertEquals(SignInError.InvalidToken, component.state.value.error)
        assertFalse(component.state.value.isValidating)
    }

    @Test
    fun aTransientFailure_surfacesTheUnavailableError() = runTest {
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
