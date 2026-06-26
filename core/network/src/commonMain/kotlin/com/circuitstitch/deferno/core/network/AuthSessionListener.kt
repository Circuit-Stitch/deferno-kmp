package com.circuitstitch.deferno.core.network

/**
 * Notified of the Active Account bearer's auth outcome on each request (#297), so the read surfaces
 * (Tasks / Search / Plan) can surface an expired session instead of silently serving stale cache.
 *
 * Only requests that went out carrying the **Active Account's** bearer report here: a `401` means
 * that session is dead ([onActiveSessionUnauthorized]); a `2xx` means it is healthy and any stale
 * banner should clear ([onActiveSessionAuthorized]). A candidate-token sign-in validation (an explicit
 * `Authorization` the bearer plugin leaves untouched) and an unauthenticated bootstrap call never
 * report — so a wrong pasted PAT can't flag the Active Account, and a missing token isn't "expired".
 *
 * Transport / timeout / TLS failures produce **no response**, so they never reach this listener — a
 * network blip can't be mistaken for an expired session, and offline-first keeps caching silently.
 *
 * A **port** owned by `core:network` (like [BearerTokenProvider]): the client calls it, but the
 * *state* — the process-wide re-auth coordinator — lives in `core:data`, which implements this.
 */
interface AuthSessionListener {
    /** An Active-Account request was rejected with `401`: its credential is invalid/expired. */
    fun onActiveSessionUnauthorized()

    /** An Active-Account request succeeded (`2xx`): the session is valid; clear any expiry flag. */
    fun onActiveSessionAuthorized()

    /** A no-op listener for call sites/tests that don't care about session expiry. */
    companion object Noop : AuthSessionListener {
        override fun onActiveSessionUnauthorized() = Unit
        override fun onActiveSessionAuthorized() = Unit
    }
}
