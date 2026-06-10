package com.circuitstitch.deferno.core.data.auth

import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.coroutines.cancellation.CancellationException

/**
 * Process-global hand-off for the OAuth redirect on iOS (ADR-0026). The system browser returns to the
 * app via the custom URL scheme (`com.circuitstitch.deferno://auth`); the Swift app's `onOpenURL` /
 * `application(_:open:)` handler must forward that URL here through [publish], which the in-flight
 * [IosBrowserAuthenticator] awaits. Single-threaded UI delivery, so no extra synchronisation.
 */
object IosAuthRedirectInbox {
    private var pending: CompletableDeferred<String>? = null

    fun expect(): CompletableDeferred<String> {
        pending?.cancel()
        return CompletableDeferred<String>().also { pending = it }
    }

    /** Forward a captured redirect URI to the waiting authenticator (call from the Swift URL handler). */
    fun publish(redirectUri: String) {
        pending?.complete(redirectUri)
        pending = null
    }

    fun clear() {
        pending = null
    }
}

/**
 * iOS [BrowserAuthenticator] (ADR-0026): opens the authorize URL in the **system browser** via
 * `UIApplication.openURL` (Safari — not an embedded WebView, so external-IdP SSO + password managers
 * work) and awaits the redirect the Swift app routes through [IosAuthRedirectInbox]. The fixed custom
 * URL scheme is both registered and used for the exchange.
 *
 * NOTE (follow-up, device-verified on macOS — ADR-0006): the preferred iOS surface is
 * `ASWebAuthenticationSession` (an ephemeral in-app browser that auto-dismisses on redirect and needs
 * no Info.plist scheme); it requires a presentation-anchor from the Swift layer and is swapped in there.
 * This `openURL` + inbox path is the portable v1 implementation and satisfies the "system browser, not
 * WebView" requirement. The Swift app must register the `com.circuitstitch.deferno` URL scheme
 * (`CFBundleURLTypes`) and forward incoming URLs to [IosAuthRedirectInbox.publish].
 */
class IosBrowserAuthenticator : BrowserAuthenticator {

    override val registrationRedirectUri: String = "com.circuitstitch.deferno://auth"

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect {
        val redirectUri = registrationRedirectUri
        val deferred = IosAuthRedirectInbox.expect()
        val url = NSURL.URLWithString(buildAuthorizeUrl(redirectUri))
        if (url == null) {
            IosAuthRedirectInbox.clear()
            return AuthRedirect.Failed("invalid authorize URL")
        }
        val application = UIApplication.sharedApplication
        if (!application.canOpenURL(url)) {
            IosAuthRedirectInbox.clear()
            return AuthRedirect.Failed("no browser available to open the authorize URL")
        }
        application.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
        return try {
            AuthRedirect.Received(callbackUri = deferred.await(), redirectUri = redirectUri)
        } catch (e: CancellationException) {
            IosAuthRedirectInbox.clear()
            throw e
        }
    }
}
