package com.circuitstitch.deferno.core.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android [BrowserAuthenticator] (ADR-0026): opens the authorize URL in the **system browser** (a plain
 * `ACTION_VIEW`, never an embedded WebView — so external-IdP SSO + password managers work) and awaits
 * the redirect the host Activity routes through the injected [AuthRedirectInbox] (the same AppScope
 * instance `MainActivity` resolves from the DI graph, #137). The fixed custom-scheme redirect is
 * both registered and used for the exchange. (Chrome Custom Tabs — for an in-app tab that auto-dismisses
 * on redirect — is a UX follow-up; functionally the system browser is equivalent and avoids a UI dep in
 * `core:data`.)
 */
class AndroidBrowserAuthenticator(
    private val context: Context,
    private val inbox: AuthRedirectInbox,
) : BrowserAuthenticator {

    override val registrationRedirectUri: String = "com.circuitstitch.deferno://auth"

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect {
        val redirectUri = registrationRedirectUri
        val deferred = inbox.expect()
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(buildAuthorizeUrl(redirectUri)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: CancellationException) {
            inbox.clear()
            throw e
        } catch (e: Exception) {
            inbox.clear()
            return AuthRedirect.Failed("could not open the browser: ${e.message}")
        }
        return try {
            AuthRedirect.Received(callbackUri = deferred.await(), redirectUri = redirectUri)
        } catch (e: CancellationException) {
            inbox.clear()
            throw e
        }
    }
}
