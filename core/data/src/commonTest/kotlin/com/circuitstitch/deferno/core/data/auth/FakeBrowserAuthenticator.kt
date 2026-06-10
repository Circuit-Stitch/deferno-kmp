package com.circuitstitch.deferno.core.data.auth

import io.ktor.http.Url

/**
 * Test [BrowserAuthenticator] that simulates the system-browser leg without a real browser. It invokes
 * the caller's authorize-URL builder with a fixed redirect URI, reads the `state` back out of that URL,
 * and returns a redirect per the configured [outcome] — letting a SignInService test drive the happy
 * path, a user cancel, a launch failure, a CSRF-tampered state, or an `error=` redirect.
 */
class FakeBrowserAuthenticator(
    override val registrationRedirectUri: String = "com.circuitstitch.deferno://auth",
    private val outcome: Outcome = Outcome.Success,
) : BrowserAuthenticator {

    enum class Outcome { Success, Cancelled, Failed, TamperedState, ErrorRedirect }

    var authenticateCalls = 0
        private set
    var capturedAuthorizeUrl: String? = null
        private set

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect {
        authenticateCalls++
        val redirectUri = registrationRedirectUri
        val authorizeUrl = buildAuthorizeUrl(redirectUri).also { capturedAuthorizeUrl = it }
        val state = Url(authorizeUrl).parameters["state"]
        return when (outcome) {
            Outcome.Success -> AuthRedirect.Received("$redirectUri?code=auth-code-1&state=$state", redirectUri)
            Outcome.TamperedState -> AuthRedirect.Received("$redirectUri?code=auth-code-1&state=tampered", redirectUri)
            Outcome.ErrorRedirect -> AuthRedirect.Received("$redirectUri?error=access_denied&state=$state", redirectUri)
            Outcome.Cancelled -> AuthRedirect.Cancelled
            Outcome.Failed -> AuthRedirect.Failed("could not open the browser")
        }
    }
}
