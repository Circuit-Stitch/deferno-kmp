package com.circuitstitch.deferno.core.data.auth

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.coroutines.cancellation.CancellationException

/**
 * iOS [BrowserAuthenticator] (ADR-0026): opens the authorize URL in the **system browser** via
 * `UIApplication.openURL` (Safari — not an embedded WebView, so external-IdP SSO + password managers
 * work) and awaits the redirect the Swift app routes through the injected [AuthRedirectInbox] (the
 * same AppScope instance `DefernoRoot.forwardAuthRedirect` publishes to, #137). The fixed custom
 * URL scheme is both registered and used for the exchange.
 *
 * NOTE (follow-up, device-verified on macOS — ADR-0006): the preferred iOS surface is
 * `ASWebAuthenticationSession` (an ephemeral in-app browser that auto-dismisses on redirect and needs
 * no Info.plist scheme); it requires a presentation-anchor from the Swift layer and is swapped in there.
 * This `openURL` + inbox path is the portable v1 implementation and satisfies the "system browser, not
 * WebView" requirement. The Swift app must register the `com.circuitstitch.deferno` URL scheme
 * (`CFBundleURLTypes`) and forward incoming URLs via `DefernoRoot.forwardAuthRedirect`.
 */
class IosBrowserAuthenticator(
    private val inbox: AuthRedirectInbox,
) : BrowserAuthenticator {

    override val registrationRedirectUri: String = "com.circuitstitch.deferno://auth"

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect {
        val redirectUri = registrationRedirectUri
        val deferred = inbox.expect()
        val url = NSURL.URLWithString(buildAuthorizeUrl(redirectUri))
        if (url == null) {
            inbox.clear()
            return AuthRedirect.Failed("invalid authorize URL")
        }
        val application = UIApplication.sharedApplication
        if (!application.canOpenURL(url)) {
            inbox.clear()
            return AuthRedirect.Failed("no browser available to open the authorize URL")
        }
        application.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
        return try {
            AuthRedirect.Received(callbackUri = deferred.await(), redirectUri = redirectUri)
        } catch (e: CancellationException) {
            inbox.clear()
            throw e
        }
    }
}
