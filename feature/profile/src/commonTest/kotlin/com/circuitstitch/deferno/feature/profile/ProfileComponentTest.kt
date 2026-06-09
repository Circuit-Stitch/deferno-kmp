package com.circuitstitch.deferno.feature.profile

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DefaultProfileComponent] (#70): on creation it fetches `/auth/me` and maps the [MeResult] to the
 * observable [ProfileState] the View renders — signed-in identity, re-auth-required (a 401), or a
 * transient unavailable — [ProfileComponent.onRetry] re-fetches, and [ProfileComponent.onSignOut]
 * emits [ProfileComponent.Output.SignOutRequested] for the host (the secure-wipe is the shell's job,
 * ADR-0009/0012). Driven on the JVM-fast path (ADR-0006) with a `StandardTestDispatcher` so the init
 * fetch is observable as `Loading` first.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drives the scheduler past the init fetch.
class ProfileComponentTest {

    private val account = Account(AccountId("work"), "Work")

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

    private fun TestScope.profileComponent(
        repo: FakeAuthRepository,
        output: (ProfileComponent.Output) -> Unit = {},
    ) = DefaultProfileComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        authRepository = repo,
        account = account,
        output = output,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun startsLoadingThenRendersTheSignedInUser() = runTest {
        val component = profileComponent(FakeAuthRepository(MeResult.Authenticated(user)))

        // The init fetch hasn't run yet under the StandardTestDispatcher.
        assertEquals(ProfileState.Loading, component.state.value)

        advanceUntilIdle()

        assertEquals(ProfileState.SignedIn(user), component.state.value)
    }

    @Test
    fun exposesTheActiveAccountForTheControls() = runTest {
        val component = profileComponent(FakeAuthRepository(MeResult.Authenticated(user)))
        assertEquals(account, component.account)
    }

    @Test
    fun a401MapsToReauthRequired() = runTest {
        val component = profileComponent(FakeAuthRepository(MeResult.Unauthorized))

        advanceUntilIdle()

        assertEquals(ProfileState.ReauthRequired, component.state.value)
    }

    @Test
    fun aTransientFailureMapsToUnavailable() = runTest {
        val component = profileComponent(FakeAuthRepository(MeResult.Unavailable))

        advanceUntilIdle()

        assertEquals(ProfileState.Unavailable, component.state.value)
    }

    @Test
    fun onRetryReFetchesAndCanRecover() = runTest {
        val repo = FakeAuthRepository(MeResult.Unavailable)
        val component = profileComponent(repo)
        advanceUntilIdle()
        assertEquals(ProfileState.Unavailable, component.state.value)

        repo.result = MeResult.Authenticated(user)
        component.onRetry()
        advanceUntilIdle()

        assertEquals(ProfileState.SignedIn(user), component.state.value)
        assertEquals(2, repo.loadCount)
    }

    @Test
    fun onSignOut_emitsSignOutRequestedForTheHost() = runTest {
        val outputs = mutableListOf<ProfileComponent.Output>()
        val component = profileComponent(FakeAuthRepository(MeResult.Authenticated(user)), output = outputs::add)
        advanceUntilIdle()

        component.onSignOut()

        // The feature never touches AccountManager — it asks the shell to secure-wipe (ADR-0009/0012).
        assertEquals(listOf<ProfileComponent.Output>(ProfileComponent.Output.SignOutRequested), outputs)
    }
}
