package com.circuitstitch.deferno.core.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

/**
 * Process-global hand-off for the OAuth redirect (ADR-0026). The system browser returns to the app via
 * the custom-scheme intent-filter (`com.circuitstitch.deferno://auth`), which the host Activity routes
 * here through [publish]; the in-flight [AndroidBrowserAuthenticator] awaits the deferred from [expect].
 * Modal sign-in, so one outstanding request at a time — a new [expect] cancels any stale one.
 */
object AuthRedirectInbox {
    private var pending: CompletableDeferred<String>? = null

    @Synchronized
    fun expect(): CompletableDeferred<String> {
        pending?.cancel()
        return CompletableDeferred<String>().also { pending = it }
    }

    /** Deliver a captured redirect URI to the waiting authenticator (a no-op if none is waiting). */
    @Synchronized
    fun publish(redirectUri: String) {
        pending?.complete(redirectUri)
        pending = null
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}

/**
 * Android [BrowserAuthenticator] (ADR-0026): opens the authorize URL in the **system browser** (a plain
 * `ACTION_VIEW`, never an embedded WebView — so external-IdP SSO + password managers work) and awaits
 * the redirect the host Activity routes through [AuthRedirectInbox]. The fixed custom-scheme redirect is
 * both registered and used for the exchange. (Chrome Custom Tabs — for an in-app tab that auto-dismisses
 * on redirect — is a UX follow-up; functionally the system browser is equivalent and avoids a UI dep in
 * `core:data`.)
 */
class AndroidBrowserAuthenticator(private val context: Context) : BrowserAuthenticator {

    override val registrationRedirectUri: String = "com.circuitstitch.deferno://auth"

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect {
        val redirectUri = registrationRedirectUri
        val deferred = AuthRedirectInbox.expect()
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(buildAuthorizeUrl(redirectUri)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: CancellationException) {
            AuthRedirectInbox.clear()
            throw e
        } catch (e: Exception) {
            AuthRedirectInbox.clear()
            return AuthRedirect.Failed("could not open the browser: ${e.message}")
        }
        return try {
            AuthRedirect.Received(callbackUri = deferred.await(), redirectUri = redirectUri)
        } catch (e: CancellationException) {
            AuthRedirectInbox.clear()
            throw e
        }
    }
}
