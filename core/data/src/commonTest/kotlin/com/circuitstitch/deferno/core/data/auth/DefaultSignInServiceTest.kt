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
    private val deviceName = DeviceName("Deferno Test — CI")

    private fun service(
        remote: FakeAuthRemoteSource,
        nativeAuth: NativeAuthRemoteSource = FakeNativeAuthRemoteSource(),
        browser: BrowserAuthenticator = FakeBrowserAuthenticator(),
        clientStore: OAuthClientStore = InMemoryOAuthClientStore(),
        manager: DefaultAccountManager = this.manager,
    ) = DefaultSignInService(remote, manager, nativeAuth, browser, clientStore, deviceName)

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

        val result = service(remote, manager = throwingManager).signIn("pat-xyz")

        assertEquals(SignInResult.Unavailable, result)
    }

    // --- browser OAuth + PKCE path (ADR-0026) ---

    @Test
    fun browser_happyPath_mintsTokenAndCommitsAccountWithTokenId() = runTest {
        val remote = FakeAuthRemoteSource(MeResult.Authenticated(user))
        val nativeAuth = FakeNativeAuthRemoteSource() // mints token "minted-pat" / id "tok-id-1"
        val browser = FakeBrowserAuthenticator(outcome = FakeBrowserAuthenticator.Outcome.Success)

        val result = service(remote, nativeAuth = nativeAuth, browser = browser).signInWithBrowser()

        val success = assertIs<SignInResult.Success>(result)
        assertEquals(AccountId("4f1c-user"), success.account.id) // id = backend User id
        assertEquals("Ada Lovelace", success.account.label)
        assertEquals("tok-id-1", success.account.tokenId) // server token id carried for revoke (ADR-0026)
        assertEquals("minted-pat", vault.getBearerToken(success.account.id)) // the minted PAT is vaulted
        assertEquals(success.account, manager.activeAccount.value)
        // The exchange echoed the exact redirect_uri the browser captured (binding check).
        assertEquals(browser.registrationRedirectUri, nativeAuth.lastExchangeRedirectUri)
        assertEquals(1, nativeAuth.exchangeCalls)
    }

    @Test
    fun browser_userCancelled_returnsCancelled_andCreatesNoAccount() = runTest {
        val nativeAuth = FakeNativeAuthRemoteSource()
        val result = service(
            FakeAuthRemoteSource(MeResult.Authenticated(user)),
            nativeAuth = nativeAuth,
            browser = FakeBrowserAuthenticator(outcome = FakeBrowserAuthenticator.Outcome.Cancelled),
        ).signInWithBrowser()

        assertEquals(SignInResult.Cancelled, result)
        assertTrue(manager.accounts.value.isEmpty())
        assertEquals(0, nativeAuth.exchangeCalls) // never exchanged
    }

    @Test
    fun browser_launchFailed_returnsUnavailable() = runTest {
        val result = service(
            FakeAuthRemoteSource(MeResult.Authenticated(user)),
            browser = FakeBrowserAuthenticator(outcome = FakeBrowserAuthenticator.Outcome.Failed),
        ).signInWithBrowser()

        assertEquals(SignInResult.Unavailable, result)
        assertTrue(manager.accounts.value.isEmpty())
    }

    @Test
    fun browser_tamperedState_isRejected_withoutExchangingTheCode() = runTest {
        // A returned state that doesn't match the one we sent is a CSRF signal — abort before exchange.
        val nativeAuth = FakeNativeAuthRemoteSource()
        val result = service(
            FakeAuthRemoteSource(MeResult.Authenticated(user)),
            nativeAuth = nativeAuth,
            browser = FakeBrowserAuthenticator(outcome = FakeBrowserAuthenticator.Outcome.TamperedState),
        ).signInWithBrowser()

        assertEquals(SignInResult.Unavailable, result)
        assertEquals(0, nativeAuth.exchangeCalls)
        assertTrue(manager.accounts.value.isEmpty())
    }

    @Test
    fun browser_errorRedirect_returnsUnavailable_withoutExchanging() = runTest {
        val nativeAuth = FakeNativeAuthRemoteSource()
        val result = service(
            FakeAuthRemoteSource(MeResult.Authenticated(user)),
            nativeAuth = nativeAuth,
            browser = FakeBrowserAuthenticator(outcome = FakeBrowserAuthenticator.Outcome.ErrorRedirect),
        ).signInWithBrowser()

        assertEquals(SignInResult.Unavailable, result)
        assertEquals(0, nativeAuth.exchangeCalls)
    }

    @Test
    fun browser_registrationFails_returnsUnavailable_withoutOpeningTheBrowser() = runTest {
        val nativeAuth = FakeNativeAuthRemoteSource().apply { registerResult = FakeNativeAuthRemoteSource.failure() }
        val browser = FakeBrowserAuthenticator()
        val result = service(
            FakeAuthRemoteSource(MeResult.Authenticated(user)),
            nativeAuth = nativeAuth,
            browser = browser,
        ).signInWithBrowser()

        assertEquals(SignInResult.Unavailable, result)
        assertEquals(0, browser.authenticateCalls) // bailed before the browser leg
    }

    @Test
    fun browser_exchangeFails_returnsUnavailable() = runTest {
        val nativeAuth = FakeNativeAuthRemoteSource().apply { exchangeResult = FakeNativeAuthRemoteSource.failure(400) }
        val result = service(
            FakeAuthRemoteSource(MeResult.Authenticated(user)),
            nativeAuth = nativeAuth,
        ).signInWithBrowser()

        assertEquals(SignInResult.Unavailable, result)
        assertTrue(manager.accounts.value.isEmpty())
    }

    @Test
    fun browser_cachesTheClientId_registeringOnlyOnce() = runTest {
        val nativeAuth = FakeNativeAuthRemoteSource()
        val store = InMemoryOAuthClientStore()
        val svc = service(FakeAuthRemoteSource(MeResult.Authenticated(user)), nativeAuth = nativeAuth, clientStore = store)

        svc.signInWithBrowser()
        svc.signInWithBrowser()

        assertEquals(1, nativeAuth.registerCalls) // second sign-in reused the cached client_id
    }

    /** A vault whose write fails the way the Keychain/Keystore does on a misconfigured build. */
    private object ThrowingSecretVault : SecretVault {
        override fun putBearerToken(account: AccountId, token: String): Unit =
            throw SecureStorageException("simulated secure-storage write failure")

        override fun getBearerToken(account: AccountId): String? = null

        override fun deleteBearerToken(account: AccountId) = Unit
    }
}
