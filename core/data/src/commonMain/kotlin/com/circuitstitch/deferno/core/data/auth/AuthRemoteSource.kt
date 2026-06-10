package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.model.User

/**
 * The outcome of fetching the Active Account's identity (`GET /auth/me`, #20). Three disjoint
 * cases the layers above react to differently — the distinction is the whole point of the type
 * (a bare `User?` would conflate "session expired" with "couldn't reach the server"):
 *
 * - [Authenticated] — the signed-in [User], already condensed to the domain model at the network
 *   boundary (ADR-0011). The happy path the screen renders.
 * - [Unauthorized] — an HTTP `401`: the Active Account's PAT is invalid/expired (CONTRACT-NOTES →
 *   "Auth"; the verified empty-body 401). The repository turns this into a re-auth request scoped to
 *   that Account (ADR-0002) — see [DefaultAuthRepository].
 * - [Unavailable] — any other failure (transport/TLS, a non-401 server error, an out-of-window
 *   envelope version): transient, leaves the Account signed in. A retry is the right response, not
 *   re-auth — so it is kept distinct from [Unauthorized].
 *
 * Deliberately free of `core:network` types ([com.circuitstitch.deferno.core.network.ApiError]):
 * the network failure shapes are mapped to these three at the remote source, so nothing above
 * `core:data` depends on the wire error model.
 */
sealed interface MeResult {
    data class Authenticated(val user: User) : MeResult
    data object Unauthorized : MeResult
    data object Unavailable : MeResult
}

/**
 * The `/auth/me` network port (#20, ADR-0001/0004): fetches the Active Account's identity and maps
 * the wire response to a [MeResult] at the boundary, so the repository above never touches a wire
 * DTO or a `core:network` error type. The Active Account's PAT is attached by the shared client's
 * bearer plugin (it resolves the token through `AccountContext` per request, ADR-0012), so this
 * source carries no credential itself — it only issues the call and condenses the result.
 *
 * [KtorAuthRemoteSource] is the production implementation over the shared Deferno `HttpClient`.
 */
interface AuthRemoteSource {
    /** Issues `GET /auth/me` for whoever is the Active Account right now and condenses the result. */
    suspend fun fetchMe(): MeResult

    /**
     * Issues `GET /auth/me` carrying [token] as an **explicit** bearer — validating a *candidate*
     * PAT during sign-in (#15, ADR-0023), before any Account holds it. The shared client's bearer
     * plugin leaves an already-set `Authorization` untouched, so this bypasses the Active-Account
     * provider. Same condensation as [fetchMe]: [MeResult.Unauthorized] means the pasted token is
     * invalid/expired, [MeResult.Unavailable] is transient.
     */
    suspend fun fetchMe(token: String): MeResult

    /**
     * Revokes the API token with [tokenId] server-side (`DELETE /auth/tokens/{id}`), authenticating
     * with [token] as an **explicit** bearer (the token being revoked). The self-service sign-out
     * revoke for browser-minted accounts (ADR-0026 / #310): the account knows its own token id, so it
     * can drop the credential on the server, not just locally. **Best-effort** — returns `true` on a
     * `2xx`/`204`, `false` on any failure (offline, already-revoked); sign-out must not block on it.
     */
    suspend fun revokeToken(tokenId: String, token: String): Boolean
}
