package com.circuitstitch.deferno.core.data.auth

import java.net.URI
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The desktop loopback [BrowserAuthenticator] (ADR-0026), JVM-fast path (ADR-0006). The "browser" is
 * simulated by [openBrowser] firing an HTTP GET at the bound loopback redirect — no real browser — so
 * the test proves the listener binds an ephemeral port, hands that port-bearing redirect URI to the
 * authorize-URL builder, and captures the redirect's `code`/`state`.
 */
class LoopbackBrowserAuthenticatorTest {

    @Test
    fun capturesTheRedirectFromTheLoopbackListener() = runTest {
        // The fake browser GETs the bound redirect URI with a code+state, on its own thread so the
        // authenticator's blocking accept() can run.
        val authenticator = LoopbackBrowserAuthenticator(openBrowser = { redirectUri ->
            thread {
                runCatching {
                    URI("$redirectUri?code=test-code&state=test-state").toURL().openStream().use { it.readBytes() }
                }
            }
        })

        // In this test the authorize-URL builder just echoes the redirect URI, so `openBrowser` receives
        // the loopback URL to hit directly.
        val result = authenticator.authenticate { redirectUri -> redirectUri }

        val received = assertIs<AuthRedirect.Received>(result)
        assertTrue(received.callbackUri.contains("code=test-code"), received.callbackUri)
        assertTrue(received.callbackUri.contains("state=test-state"), received.callbackUri)
        // The redirect URI used carries the dynamically-bound loopback port and the /callback path.
        assertTrue(received.redirectUri.startsWith("http://127.0.0.1:"), received.redirectUri)
        assertTrue(received.redirectUri.endsWith("/callback"), received.redirectUri)
        assertEquals(received.redirectUri, received.callbackUri.substringBefore('?'))
    }

    @Test
    fun registrationRedirectUriIsPortAgnosticLoopback() {
        assertEquals("http://127.0.0.1/callback", LoopbackBrowserAuthenticator().registrationRedirectUri)
    }

    @Test
    fun reportsFailureWhenTheBrowserCannotOpen() = runTest {
        val authenticator = LoopbackBrowserAuthenticator(openBrowser = { error("no browser") })

        val result = authenticator.authenticate { it }

        assertIs<AuthRedirect.Failed>(result)
    }
}
