package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.network.ApiResult

/** A registered native OAuth client (RFC 7591): the issued [clientId], cached per-install. */
data class ClientRegistration(val clientId: String)

/**
 * A freshly minted personal access token from the browser sign-in exchange. [token] is the durable
 * bearer credential (vaulted, never logged — ADR-0009); [tokenId] is its server-side id, used to
 * revoke it on sign-out (`DELETE /auth/tokens/{id}`).
 */
data class MintedToken(val token: String, val tokenId: String)

/**
 * The native browser sign-in network port (OAuth Authorization Code + PKCE, backend #299, ADR-0026):
 * dynamic client registration, the browser authorize URL, and the code→token exchange. These calls are
 * **unauthenticated** — there is no Active Account yet, so the shared client's bearer plugin attaches
 * nothing (it skips when `currentToken()` is null, `DefernoHttpClient`). Wire shapes:
 * `contracts/CONTRACT-NOTES.md` → "Native browser sign-in".
 *
 * [KtorNativeAuthRemoteSource] is the production implementation over the shared Deferno `HttpClient`.
 */
interface NativeAuthRemoteSource {
    /**
     * Registers a public client for [redirectUri] (RFC 7591) named [clientName], returning the issued
     * client id. Open + rate-limited; the caller caches the result per install.
     */
    suspend fun register(redirectUri: String, clientName: String): ApiResult<ClientRegistration>

    /**
     * The browser entry URL: `GET …/auth/native/authorize` carrying [clientId], [redirectUri], the
     * PKCE [codeChallenge] (S256), and the CSRF [state]. Pure URL construction — opened in the system
     * browser, never fetched by the client. The browser owns password + MFA + SSO and the backend then
     * `302`s to `redirect_uri?code=…&state=…`.
     */
    fun authorizeUrl(clientId: String, redirectUri: String, codeChallenge: String, state: String): String

    /**
     * Exchanges the one-time [code] (with its [codeVerifier]) for a minted PAT, tagging it [deviceName].
     * [clientId] + [redirectUri] must match the code's server-side binding. A bad/expired code surfaces
     * as an [ApiResult.Failure] (`400 invalid grant`).
     */
    suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        clientId: String,
        redirectUri: String,
        deviceName: String,
    ): ApiResult<MintedToken>
}
