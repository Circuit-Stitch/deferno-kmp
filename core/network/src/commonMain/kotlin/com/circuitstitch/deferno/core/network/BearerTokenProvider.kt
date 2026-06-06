package com.circuitstitch.deferno.core.network

/**
 * Supplies the bearer token (the Active Account's personal access token, ADR-0012) to attach
 * to outgoing requests. A **port** owned by `core:network`: the client reads it on every
 * request, but the *source* of the token — the Active Account, resolved through
 * `AccountContext`, and that Account's PAT in the `SecretVault` — lives in `core:data`, which
 * sits above `core:network` in the layering (ADR-0004). The data layer implements this
 * interface; the network layer never depends up onto secure storage or the account boundary.
 *
 * [currentToken] is read **fresh on each request**, never cached: switching the Active Account
 * (ADR-0002) must immediately re-point the credential without rebuilding the client. Returns
 * `null` when no Account is active / signed in, in which case the request goes out without an
 * `Authorization` header (e.g. the unauthenticated bootstrap calls).
 *
 * Implementations must never log the token they return (ADR-0009).
 */
fun interface BearerTokenProvider {
    /** The bearer token for whoever is the Active Account right now, or `null` if none. */
    fun currentToken(): String?
}
