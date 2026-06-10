package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.data.account.DefaultAccountManager
import com.circuitstitch.deferno.core.data.account.InMemoryAccountRegistry
import com.circuitstitch.deferno.core.data.account.NoOpAccountDataStore
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.secure.InMemorySecretVault
import com.circuitstitch.deferno.core.secure.SecretVault
import com.circuitstitch.deferno.core.secure.SecureStorageException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DefaultSignInService] (#15, ADR-0023): validate-then-commit. Driven over the real
 * [DefaultAccountManager] (in-memory registry + vault) so the test proves the *whole* commit — a
 * verified token lands in the secure vault under an Account keyed by the backend User id, and that
 * Account becomes active — not just that a result type is returned. A rejected or unreachable token
 * must create **no** Account (ADR-0023). The `/auth/me` validation itself is faked.
 */
class DefaultSignInServiceTest {

    private val user = User(
        id = UserId("4f1c-user"),
        username = "ada",
        displayName = "Ada Lovelace",
        role = "member",
        personalOrgId = OrgId("org-1"),
        orgSlug = "u-ada42",
        isAdmin = false,
        consoleUrl = null,
    )

    private val vault = InMemorySecretVault()
    private val manager = DefaultAccountManager(InMemoryAccountRegistry(), vault, NoOpAccountDataStore)

    private fun service(remote: FakeAuthRemoteSource) = DefaultSignInService(remote, manager)

    @Test
    fun validToken_createsTheAccount_vaultsTheToken_andMakesItActive() = runTest {
        val remote = FakeAuthRemoteSource(MeResult.Unavailable).apply {
            candidateResult = MeResult.Authenticated(user)
        }

        val result = service(remote).signIn("pat-xyz")

        val success = assertIs<SignInResult.Success>(result)
        // Account id = backend User id; label = display name (ADR-0023).
        assertEquals(AccountId("4f1c-user"), success.account.id)
        assertEquals("Ada Lovelace", success.account.label)
        // The token was the one validated, and it is now vaulted + active.
        assertEquals("pat-xyz", remote.lastToken)
        assertEquals("pat-xyz", vault.getBearerToken(success.account.id))
        assertEquals(success.account, manager.activeAccount.value)
    }

    @Test
    fun blankDisplayName_labelsTheAccountFromTheUsername() = runTest {
        // Whitespace-only, not just empty — pins the `.ifBlank` fallback (not `.ifEmpty`).
        val remote = FakeAuthRemoteSource(MeResult.Authenticated(user.copy(displayName = "   ")))

        val result = service(remote).signIn("pat")

        assertEquals("ada", assertIs<SignInResult.Success>(result).account.label)
    }

    @Test
    fun invalidToken_returnsInvalidToken_andCreatesNoAccount() = runTest {
        val remote = FakeAuthRemoteSource(MeResult.Unauthorized)

        val result = service(remote).signIn("bad-token")

        assertEquals(SignInResult.InvalidToken, result)
        assertTrue(manager.accounts.value.isEmpty())
        assertNull(manager.activeAccount.value)
    }

    @Test
    fun transientFailure_returnsUnavailable_andCreatesNoAccount() = runTest {
        val remote = FakeAuthRemoteSource(MeResult.Unavailable)

        val result = service(remote).signIn("pat")

        assertEquals(SignInResult.Unavailable, result)
        assertTrue(manager.accounts.value.isEmpty())
        assertNull(manager.activeAccount.value)
    }

    @Test
    fun secureStorageThrows_returnsUnavailable_ratherThanCrashing() = runTest {
        // A VALIDATED token whose Account establishment throws (the secure vault rejects the write — e.g.
        // an unsigned iOS build has no Keychain entitlement, so SecItemAdd returns
        // errSecMissingEntitlement) must surface Unavailable, NOT let the exception escape signIn() and
        // abort the app's sign-in coroutine (ADR-0009/0023). Without the guard this throws.
        val throwingManager =
            DefaultAccountManager(InMemoryAccountRegistry(), ThrowingSecretVault, NoOpAccountDataStore)
        val remote = FakeAuthRemoteSource(MeResult.Authenticated(user))

        val result = DefaultSignInService(remote, throwingManager).signIn("pat-xyz")

        assertEquals(SignInResult.Unavailable, result)
    }

    /** A vault whose write fails the way the Keychain/Keystore does on a misconfigured build. */
    private object ThrowingSecretVault : SecretVault {
        override fun putBearerToken(account: AccountId, token: String): Unit =
            throw SecureStorageException("simulated secure-storage write failure")

        override fun getBearerToken(account: AccountId): String? = null

        override fun deleteBearerToken(account: AccountId) = Unit
    }
}
