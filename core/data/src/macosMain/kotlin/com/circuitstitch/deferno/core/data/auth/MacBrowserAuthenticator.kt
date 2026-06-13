package com.circuitstitch.deferno.core.data.auth

/**
 * macOS browser sign-in (ADR-0026 / ADR-0029) — **stub for Phase 0**. The Phase-0 macOS app renders
 * the shared shell over the in-memory demo with no backend, so this leg is never driven. The real
 * macOS implementation (`ASWebAuthenticationSession` anchored to the app's `NSWindow`, the twin of
 * [IosBrowserAuthenticator]'s `UIWindow` anchor) lands with sign-in in Phase 1b; until then every
 * call returns [AuthRedirect.Failed].
 *
 * ponytail: a stub, not a half-wired session — Phase 1b owns the real macOS browser leg.
 */
class MacBrowserAuthenticator : BrowserAuthenticator {
    override val registrationRedirectUri: String = "$CALLBACK_SCHEME://auth"

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect =
        AuthRedirect.Failed("browser sign-in not yet wired on macOS (Phase 1b)")

    private companion object {
        const val CALLBACK_SCHEME = "com.circuitstitch.deferno"
    }
}
