package com.circuitstitch.deferno.core.data.auth

/**
 * Drives the system-browser leg of native sign-in (RFC 8252, ADR-0026): opens the authorize URL in the
 * OS browser — Android Custom Tabs, iOS `ASWebAuthenticationSession`, desktop default browser, **never**
 * an embedded WebView (which would break external-IdP SSO + password managers) — and suspends until the
 * backend redirects back to the app, returning the captured redirect.
 *
 * Platform-specific by nature: each OS exposes a different browser-auth API and redirect-capture
 * mechanism, and each owns its `redirect_uri` (a fixed custom scheme / verified App-Link on mobile, a
 * freshly-bound loopback on desktop). So this is a port with per-platform implementations bound via DI —
 * the desktop [LoopbackBrowserAuthenticator], an Android Custom Tabs impl, and an iOS
 * `ASWebAuthenticationSession` impl. Implementations must never log the captured redirect (it carries
 * the one-time authorization code).
 */
interface BrowserAuthenticator {
    /**
     * The `redirect_uri` to register with the authorization server (RFC 7591). Stable per install:
     * the fixed custom scheme / App-Link on mobile, or a port-agnostic loopback on desktop (the backend
     * ignores the loopback port at exchange, RFC 8252 §7.3). The per-attempt redirect URI handed to
     * [authenticate]'s builder may differ only by loopback port.
     */
    val registrationRedirectUri: String

    /**
     * Runs the browser leg. The implementation chooses the concrete `redirect_uri` for this attempt
     * (identical to [registrationRedirectUri] on mobile; a freshly-bound loopback on desktop), passes it
     * to [buildAuthorizeUrl] to construct the authorize URL, opens the system browser, and suspends
     * until the redirect is captured — or the user cancels / it fails. Cancelling the calling coroutine
     * must tear the browser session / listener down.
     */
    suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect
}

/** The outcome of a browser sign-in leg ([BrowserAuthenticator.authenticate]). */
sealed interface AuthRedirect {
    /**
     * The browser redirected back to the app. [callbackUri] is the full redirect URI carrying
     * `?code=…&state=…` (or `?error=…`); [redirectUri] is the exact `redirect_uri` used for this attempt,
     * which the token exchange must echo for the server-side binding check.
     */
    data class Received(val callbackUri: String, val redirectUri: String) : AuthRedirect

    /** The user dismissed the browser without completing sign-in. */
    data object Cancelled : AuthRedirect

    /** The browser couldn't be opened, or capturing the redirect failed (not a user cancel). */
    data class Failed(val reason: String) : AuthRedirect
}
