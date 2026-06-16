package com.circuitstitch.deferno.core.data.auth

import java.net.InetSocketAddress
import java.net.Socket
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
    fun ignoresStrayRequestsBeforeTheRealRedirect() = runTest {
        // A browser fires a favicon/preconnect GET (no code/state) before the redirect lands. The
        // listener must 404 it and keep waiting, then capture the real redirect. The favicon hit is
        // awaited (its 404 throws, swallowed) so the two connections stay ordered.
        val authenticator = LoopbackBrowserAuthenticator(openBrowser = { redirectUri ->
            val origin = redirectUri.substringBefore("/callback")
            thread {
                runCatching { URI("$origin/favicon.ico").toURL().openStream().use { it.readBytes() } }
                runCatching {
                    URI("$redirectUri?code=real-code&state=real-state").toURL().openStream().use { it.readBytes() }
                }
            }
        })

        val received = assertIs<AuthRedirect.Received>(authenticator.authenticate { it })
        assertTrue(received.callbackUri.contains("code=real-code"), received.callbackUri)
    }

    @Test
    fun aSilentClientDoesNotBlockTheRealRedirect() = runTest {
        // A client that connects but never sends a request line must not squat on the listener: the
        // per-connection read timeout drops it so the loop goes on to capture the real redirect.
        val authenticator = LoopbackBrowserAuthenticator(readTimeoutMs = 250, openBrowser = { redirectUri ->
            val port = URI(redirectUri).port
            thread {
                // Connect first (so this lands in accept() ahead of the redirect) and never send.
                val silent = Socket().apply { connect(InetSocketAddress("127.0.0.1", port)) }
                runCatching {
                    URI("$redirectUri?code=real-code&state=test-state").toURL().openStream().use { it.readBytes() }
                }
                silent.close()
            }
        })

        val received = assertIs<AuthRedirect.Received>(authenticator.authenticate { it })
        assertTrue(received.callbackUri.contains("code=real-code"), received.callbackUri)
    }

    @Test
    fun surfacesAnErrorRedirect() = runTest {
        // An `?error=…` redirect must be returned (not swallowed as a stray "keep waiting").
        val authenticator = LoopbackBrowserAuthenticator(openBrowser = { redirectUri ->
            thread {
                runCatching {
                    URI("$redirectUri?error=access_denied").toURL().openStream().use { it.readBytes() }
                }
            }
        })

        val received = assertIs<AuthRedirect.Received>(authenticator.authenticate { it })
        assertTrue(received.callbackUri.contains("error=access_denied"), received.callbackUri)
    }

    @Test
    fun givesUpWithCancelledWhenNoRedirectArrives() = runTest {
        // The user wandered off — no redirect ever hits the loopback. The listener must free itself
        // at ACCEPT_TIMEOUT_MS rather than wait forever, reported as a cancel (not a failure).
        val authenticator = LoopbackBrowserAuthenticator(
            acceptTimeoutMs = 200,
            openBrowser = { /* never connects back */ },
        )

        assertIs<AuthRedirect.Cancelled>(authenticator.authenticate { it })
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
