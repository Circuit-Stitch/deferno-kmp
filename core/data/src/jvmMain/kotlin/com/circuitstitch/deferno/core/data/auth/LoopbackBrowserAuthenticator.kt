package com.circuitstitch.deferno.core.data.auth

import java.awt.Desktop
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Desktop [BrowserAuthenticator] (ADR-0026): binds a loopback HTTP listener on `127.0.0.1:{ephemeral}`,
 * opens the authorize URL in the OS default browser, and blocks for the one inbound GET — the backend's
 * redirect to `http://127.0.0.1:{port}/callback?code=…&state=…` (RFC 8252 §7.3 native loopback). It
 * replies with a tiny "you can close this tab" page and returns the captured request URI.
 *
 * Registration uses a **port-agnostic** loopback ([registrationRedirectUri]); the per-attempt redirect
 * carries the freshly-bound port, which the authorization server ignores when matching loopback. The
 * blocking accept honours coroutine cancellation (the listener is closed when the calling job completes)
 * and self-frees after [ACCEPT_TIMEOUT_MS] if the user abandons the browser. [openBrowser] is injectable
 * so tests can simulate the browser without launching a real one.
 */
class LoopbackBrowserAuthenticator(
    private val openBrowser: (String) -> Unit = ::desktopBrowse,
    private val acceptTimeoutMs: Int = ACCEPT_TIMEOUT_MS,
    private val readTimeoutMs: Int = SOCKET_READ_TIMEOUT_MS,
) : BrowserAuthenticator {

    override val registrationRedirectUri: String = "http://127.0.0.1/callback"

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect =
        withContext(Dispatchers.IO) {
            val server = try {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            } catch (e: IOException) {
                return@withContext AuthRedirect.Failed("could not bind loopback listener: ${e.message}")
            }
            // Cancelling the sign-in coroutine closes the listener so the blocking accept() unblocks.
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { runCatching { server.close() } }
            try {
                val port = server.localPort
                val redirectUri = "http://127.0.0.1:$port/callback"
                try {
                    openBrowser(buildAuthorizeUrl(redirectUri))
                } catch (e: Exception) {
                    return@withContext AuthRedirect.Failed("could not open the browser: ${e.message}")
                }
                // Browsers fire stray favicon/preconnect/speculative GETs at a freshly-opened origin;
                // loop past anything without `code`/`error` (404 it, keep listening) until the real
                // redirect lands. ACCEPT_TIMEOUT_MS bounds the *total* wait, not each accept().
                val deadline = System.currentTimeMillis() + acceptTimeoutMs
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) return@withContext AuthRedirect.Cancelled
                    server.soTimeout = remaining.toInt()
                    val socket = try {
                        server.accept()
                    } catch (e: SocketTimeoutException) {
                        return@withContext AuthRedirect.Cancelled
                    } catch (e: IOException) {
                        // A close from cancellation surfaces here as a SocketException — report it as cancel.
                        return@withContext if (!coroutineContext.isActive) {
                            AuthRedirect.Cancelled
                        } else {
                            AuthRedirect.Failed("redirect capture failed: ${e.message}")
                        }
                    }
                    val callbackUri = socket.use { s ->
                        // Cap the read so a silent client can't hold the connection (and the whole budget);
                        // a real browser sends its request line immediately. A timeout → null → keep looping.
                        s.soTimeout = remaining.coerceAtMost(readTimeoutMs.toLong()).toInt()
                        val requestLine = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull()
                        // "GET /callback?code=…&state=… HTTP/1.1" → the path+query after the method.
                        val pathAndQuery = requestLine?.substringAfter(' ', "")?.substringBefore(' ').orEmpty()
                        val isCallback = pathAndQuery.substringAfter('?', "").split('&').any {
                            val key = it.substringBefore('=')
                            key == "code" || key == "error"
                        }
                        runCatching {
                            s.getOutputStream().apply {
                                write(if (isCallback) RESPONSE_BYTES else NOT_FOUND_BYTES)
                                flush()
                            }
                        }
                        if (isCallback) "http://127.0.0.1:$port$pathAndQuery" else null
                    }
                    if (callbackUri != null) {
                        return@withContext AuthRedirect.Received(callbackUri = callbackUri, redirectUri = redirectUri)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } finally {
                cancelHandle?.dispose()
                runCatching { server.close() }
            }
        }

    private companion object {
        // Generous enough for the backend's 5-min code TTL; frees the listener if the user wanders off.
        const val ACCEPT_TIMEOUT_MS = 5 * 60 * 1000

        // Per-connection read cap: a real browser sends its request line at once, so a silent client
        // that never does is dropped after this instead of squatting on the accept loop.
        const val SOCKET_READ_TIMEOUT_MS = 10 * 1000

        val RESPONSE_BYTES: ByteArray = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Connection: close\r\n\r\n" +
                "<!doctype html><html><body style=\"font-family:sans-serif;text-align:center;padding:3rem\">" +
                "<h2>You're signed in to Deferno.</h2><p>You can close this tab and return to the app.</p>" +
                "</body></html>"
            ).encodeToByteArray()

        // Stray favicon/preconnect requests get a terse 404 so the listener stays open for the redirect.
        val NOT_FOUND_BYTES: ByteArray = (
            "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Connection: close\r\n\r\n" +
                "Not found"
            ).encodeToByteArray()
    }
}

/** Opens [url] in the OS default browser (best-effort; throws if no desktop browser is available). */
private fun desktopBrowse(url: String) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    require(desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) { "no default browser available" }
    desktop.browse(URI(url))
}
