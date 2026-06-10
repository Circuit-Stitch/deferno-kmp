package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.secure.Pkce
import io.ktor.http.Url
import kotlin.coroutines.cancellation.CancellationException

/**
 * Production [SignInService] (#15, ADR-0012/0023/0026). Both paths converge on the same commit — resolve
 * identity via `/auth/me`, then `AccountManager.addAccount` — so the Account shape (id = backend User id,
 * label from display name) is identical whether the token came from the browser or a paste.
 *
 * [signInWithBrowser] is the proper native login: register a client (cached, RFC 7591), run the
 * system-browser OAuth + PKCE leg via [browserAuthenticator], exchange the redirected code for a minted
 * PAT, and commit it **with its server-side token id** (so sign-out can revoke it, ADR-0026). [signIn]
 * is the dev paste fallback. It holds no token itself; secrets flow through into the vault and are never
 * logged (ADR-0009).
 */
class DefaultSignInService(
    private val remoteSource: AuthRemoteSource,
    private val accountManager: AccountManager,
    private val nativeAuth: NativeAuthRemoteSource,
    private val browserAuthenticator: BrowserAuthenticator,
    private val clientStore: OAuthClientStore,
    private val deviceName: DeviceName,
) : SignInService {

    override suspend fun signInWithBrowser(): SignInResult {
        // 1. Ensure a registered public client (cached per install; register on a cache miss).
        val clientId = clientStore.clientId() ?: when (
            val reg = nativeAuth.register(browserAuthenticator.registrationRedirectUri, deviceName.value)
        ) {
            is ApiResult.Success -> reg.data.clientId.also { clientStore.storeClientId(it) }
            is ApiResult.Failure -> return SignInResult.Unavailable
        }

        // 2. Fresh PKCE pair + CSRF state for this attempt.
        val pkce = Pkce.generate()
        val state = Pkce.randomUrlSafe()

        // 3. Browser leg. The authenticator owns the concrete redirect_uri (a loopback port on desktop),
        //    so it builds the authorize URL through this callback with whatever redirect_uri it will capture.
        val redirect = browserAuthenticator.authenticate { redirectUri ->
            nativeAuth.authorizeUrl(
                clientId = clientId,
                redirectUri = redirectUri,
                codeChallenge = pkce.challenge,
                state = state,
            )
        }
        val received = when (redirect) {
            is AuthRedirect.Received -> redirect
            AuthRedirect.Cancelled -> return SignInResult.Cancelled
            is AuthRedirect.Failed -> return SignInResult.Unavailable
        }

        // 4. Validate the callback: an `error` param, a mismatched `state` (CSRF), or a missing `code`
        //    are all aborts — never proceed to exchange (a tampered state must not mint a token).
        val callback = Url(received.callbackUri)
        val code = callback.parameters["code"]
        if (callback.parameters["error"] != null) return SignInResult.Unavailable
        if (callback.parameters["state"] != state || code.isNullOrBlank()) return SignInResult.Unavailable

        // 5. Exchange the one-time code (proving the PKCE verifier) for a minted PAT.
        val minted = when (
            val ex = nativeAuth.exchangeCode(
                code = code,
                codeVerifier = pkce.verifier,
                clientId = clientId,
                redirectUri = received.redirectUri,
                deviceName = deviceName.value,
            )
        ) {
            is ApiResult.Success -> ex.data
            is ApiResult.Failure -> return SignInResult.Unavailable
        }

        // 6. Resolve identity and commit, carrying the token id so sign-out can revoke server-side.
        return when (val me = remoteSource.fetchMe(minted.token)) {
            is MeResult.Authenticated ->
                establish(me.user, minted.token, tokenId = minted.tokenId) ?: SignInResult.Unavailable
            // A freshly minted token that 401s, or any transient error, is not a credential the user can
            // fix by re-typing — it's an anomaly/transient, so retry rather than "invalid token".
            MeResult.Unauthorized, MeResult.Unavailable -> SignInResult.Unavailable
        }
    }

    override suspend fun signIn(token: String): SignInResult =
        when (val result = remoteSource.fetchMe(token)) {
            // A pasted token carries no server-side id → local-wipe-only Account (ADR-0023).
            is MeResult.Authenticated ->
                establish(result.user, token, tokenId = null) ?: SignInResult.Unavailable
            // A 401 means the pasted PAT is invalid/expired; anything else is transient. Neither
            // creates an Account — only a verified token is stored (ADR-0023).
            MeResult.Unauthorized -> SignInResult.InvalidToken
            MeResult.Unavailable -> SignInResult.Unavailable
        }

    /**
     * Establishes the Account from a verified [user] + its [token] (and optional server-side [tokenId]):
     * id = backend User id (so re-signing-in upserts rather than duplicating), label = display name
     * falling back to the login handle when blank. Returns the [SignInResult.Success], or `null` when the
     * local commit throws — the secure vault rejecting the write (e.g. an unsigned iOS build with no
     * Keychain entitlement, `errSecMissingEntitlement`) is a transient *local* failure, not a bad token,
     * so the caller maps `null` to [SignInResult.Unavailable] rather than letting it abort the coroutine
     * (ADR-0009). Cancellation still propagates so a torn-down component stops cleanly.
     */
    private suspend fun establish(user: User, token: String, tokenId: String?): SignInResult.Success? {
        val account = Account(
            id = AccountId(user.id.value),
            label = user.displayName.ifBlank { user.username },
            tokenId = tokenId,
        )
        return try {
            accountManager.addAccount(account, token)
            SignInResult.Success(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }
}
