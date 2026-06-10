package com.circuitstitch.deferno.core.data.auth

/**
 * The human-readable device label tagged onto a browser-minted token (the `name` on
 * `POST /auth/native/token`, e.g. "Deferno Android — Pixel 8", ADR-0026). A wrapper type rather than a
 * bare `String` so DI binds it unambiguously; the value is supplied per platform from the device model.
 */
data class DeviceName(val value: String)

/**
 * Caches the dynamically-registered native OAuth `client_id` (RFC 7591, ADR-0026) so the app registers
 * once and reuses it across sign-ins rather than minting a throwaway client each time. Non-secret (the
 * public client has no secret), so it may live in plain app storage.
 *
 * [InMemoryOAuthClientStore] is the v1 binding (per-process); a persistent per-platform store
 * (SharedPreferences / NSUserDefaults / java prefs) is a follow-up — until then a cold start re-registers,
 * which is harmless (registration is open + rate-limited and unused clients TTL-expire on the backend).
 */
interface OAuthClientStore {
    fun clientId(): String?
    fun storeClientId(clientId: String)
}

/** Process-lifetime [OAuthClientStore]: caches the client id in memory (the v1 binding). */
class InMemoryOAuthClientStore : OAuthClientStore {
    private var cached: String? = null
    override fun clientId(): String? = cached
    override fun storeClientId(clientId: String) { cached = clientId }
}
