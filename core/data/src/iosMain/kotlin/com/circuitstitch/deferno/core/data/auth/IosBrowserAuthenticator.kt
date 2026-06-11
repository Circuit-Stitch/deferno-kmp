package com.circuitstitch.deferno.core.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorDomain
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS [BrowserAuthenticator] (ADR-0026): runs the authorize leg in an [ASWebAuthenticationSession] —
 * the system's in-app browser sheet for OAuth, the iOS twin of Android Custom Tabs (RFC 8252). The
 * user never leaves the app: the sheet presents over it, shares Safari's cookies + password manager
 * (so external-IdP SSO works — deliberately **not** an embedded WebView, which would break both and
 * is a phishing surface), captures the redirect to the fixed custom scheme itself, and auto-dismisses
 * on completion. No Info.plist scheme round-trip is involved — the registered `CFBundleURLTypes`
 * scheme + `DefernoRoot.forwardAuthRedirect` → [AuthRedirectInbox] path remains only as a fallback
 * for externally-opened redirects (#137).
 *
 * `start()` must run on the main thread, and the session must stay strongly referenced until its
 * completion handler fires — both handled here. The user dismissing the sheet surfaces as the
 * `canceledLogin` error → [AuthRedirect.Cancelled]; cancelling the calling coroutine tears the
 * sheet down ([BrowserAuthenticator.authenticate]'s contract).
 */
class IosBrowserAuthenticator : BrowserAuthenticator {

    // The session holds its presentationContextProvider weakly; this authenticator is an AppScope
    // singleton, so anchoring the provider here keeps it alive for the whole browser leg.
    private val presentationContext = KeyWindowPresentationContext()

    override val registrationRedirectUri: String = "$CALLBACK_SCHEME://auth"

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect {
        val redirectUri = registrationRedirectUri
        val url = NSURL.URLWithString(buildAuthorizeUrl(redirectUri))
            ?: return AuthRedirect.Failed("invalid authorize URL")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val session = ASWebAuthenticationSession(
                    uRL = url,
                    callbackURLScheme = CALLBACK_SCHEME,
                    completionHandler = { callbackUrl, error ->
                        val outcome = when {
                            callbackUrl != null ->
                                callbackUrl.absoluteString
                                    ?.let { AuthRedirect.Received(callbackUri = it, redirectUri = redirectUri) }
                                    ?: AuthRedirect.Failed("redirect callback carried no URL")
                            error != null &&
                                error.domain == ASWebAuthenticationSessionErrorDomain &&
                                error.code == ASWebAuthenticationSessionErrorCodeCanceledLogin ->
                                AuthRedirect.Cancelled
                            else ->
                                AuthRedirect.Failed(error?.localizedDescription ?: "browser session failed")
                        }
                        if (continuation.isActive) continuation.resume(outcome)
                    },
                )
                session.presentationContextProvider = presentationContext
                continuation.invokeOnCancellation { session.cancel() }
                if (!session.start() && continuation.isActive) {
                    continuation.resume(AuthRedirect.Failed("could not present the sign-in browser"))
                }
            }
        }
    }

    private companion object {
        /** The registered custom scheme (Info.plist `CFBundleURLTypes`) the redirect returns on. */
        const val CALLBACK_SCHEME = "com.circuitstitch.deferno"
    }
}

/** Anchors the session's sheet on the app's key window (asked via this protocol at presentation). */
private class KeyWindowPresentationContext :
    NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(
        session: ASWebAuthenticationSession,
    ): ASPresentationAnchor? = UIApplication.sharedApplication.keyWindow ?: UIWindow()
}
