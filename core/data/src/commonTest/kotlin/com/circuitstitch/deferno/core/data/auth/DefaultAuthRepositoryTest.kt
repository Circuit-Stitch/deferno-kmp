package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.data.account.AccountContext
import com.circuitstitch.deferno.core.data.account.RecordingReauthRequester
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DefaultAuthRepository] (#20): it returns the `/auth/me` result unchanged and, on a `401`, routes
 * **only the Active Account** to re-auth (ADR-0002 hard isolation). These pin that contract on the
 * fast JVM path with a recording re-auth requester — a 401 flags exactly the active id, and the
 * happy / transient paths flag nothing.
 */
class DefaultAuthRepositoryTest {

    private val active = Account(AccountId("acc-1"), "Work")
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

    private fun repository(
        result: MeResult,
        active: Account? = this.active,
        reauth: RecordingReauthRequester = RecordingReauthRequester(),
    ) = DefaultAuthRepository(FakeAuthRemoteSource(result), accountContext(active), reauth) to reauth

    @Test
    fun authenticatedResultIsReturnedAndRaisesNoReauth() = runTest {
        val (repo, reauth) = repository(MeResult.Authenticated(user))

        assertEquals(MeResult.Authenticated(user), repo.loadMe())
        assertTrue(reauth.requested.isEmpty())
    }

    @Test
    fun unauthorizedRoutesOnlyTheActiveAccountToReauth() = runTest {
        val (repo, reauth) = repository(MeResult.Unauthorized)

        assertEquals(MeResult.Unauthorized, repo.loadMe())
        // Exactly the Active Account is flagged — never a global sign-out (ADR-0002).
        assertEquals(listOf(AccountId("acc-1")), reauth.requested)
    }

    @Test
    fun unavailableResultRaisesNoReauth() = runTest {
        val (repo, reauth) = repository(MeResult.Unavailable)

        assertEquals(MeResult.Unavailable, repo.loadMe())
        assertTrue(reauth.requested.isEmpty())
    }

    @Test
    fun a401WithNoActiveAccountRaisesNoReauth() = runTest {
        // Defensive: a request only carries a PAT when an Account is active, so this is unreachable in
        // normal flow — but a missing Active Account must raise nothing rather than crash.
        val (repo, reauth) = repository(MeResult.Unauthorized, active = null)

        assertEquals(MeResult.Unauthorized, repo.loadMe())
        assertTrue(reauth.requested.isEmpty())
    }

    private fun accountContext(active: Account?): AccountContext = object : AccountContext {
        override val activeAccount: StateFlow<Account?> = MutableStateFlow(active)
    }
}
