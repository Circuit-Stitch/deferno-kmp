package com.circuitstitch.deferno.feature.auth

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DefaultAuthComponent] (#20 tracer): on creation it fetches `/auth/me` and maps the [MeResult] to
 * the observable [AuthState] the View renders — signed-in identity, re-auth-required (a 401), or a
 * transient unavailable — and [AuthComponent.onRetry] re-fetches. Driven on the JVM-fast path
 * (ADR-0006) with a `StandardTestDispatcher` so the init fetch is observable as `Loading` first.
 */
class AuthComponentTest {

    private val user = User(
        id = UserId("1d35f62e-eed9-44de-96e8-e61a307af83f"),
        username = "sampleuser",
        displayName = "Sample User",
        role = "admin",
        personalOrgId = OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"),
        orgSlug = "u-e4h2qk",
        isAdmin = false,
        consoleUrl = null,
    )

    private fun TestScope.authComponent(repo: FakeAuthRepository) = DefaultAuthComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        authRepository = repo,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun startsLoadingThenRendersTheSignedInUser() = runTest {
        val component = authComponent(FakeAuthRepository(MeResult.Authenticated(user)))

        // The init fetch hasn't run yet under the StandardTestDispatcher.
        assertEquals(AuthState.Loading, component.state.value)

        advanceUntilIdle()

        assertEquals(AuthState.SignedIn(user), component.state.value)
    }

    @Test
    fun a401MapsToReauthRequired() = runTest {
        val component = authComponent(FakeAuthRepository(MeResult.Unauthorized))

        advanceUntilIdle()

        assertEquals(AuthState.ReauthRequired, component.state.value)
    }

    @Test
    fun aTransientFailureMapsToUnavailable() = runTest {
        val component = authComponent(FakeAuthRepository(MeResult.Unavailable))

        advanceUntilIdle()

        assertEquals(AuthState.Unavailable, component.state.value)
    }

    @Test
    fun onRetryReFetchesAndCanRecover() = runTest {
        val repo = FakeAuthRepository(MeResult.Unavailable)
        val component = authComponent(repo)
        advanceUntilIdle()
        assertEquals(AuthState.Unavailable, component.state.value)

        // The session comes back on retry — the component re-fetches and renders the user.
        repo.result = MeResult.Authenticated(user)
        component.onRetry()
        advanceUntilIdle()

        assertEquals(AuthState.SignedIn(user), component.state.value)
        assertEquals(2, repo.loadCount)
    }
}
