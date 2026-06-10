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
) : BrowserAuthenticator {

    override val registrationRedirectUri: String = "http://127.0.0.1/callback"

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect =
        withContext(Dispatchers.IO) {
            val server = try {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            } catch (e: IOException) {
                return@withContext AuthRedirect.Failed("could not bind loopback listener: ${e.message}")
            }
            server.soTimeout = ACCEPT_TIMEOUT_MS
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
                socket.use { s ->
                    val requestLine = s.getInputStream().bufferedReader().readLine()
                        ?: return@withContext AuthRedirect.Failed("empty redirect request")
                    // "GET /callback?code=…&state=… HTTP/1.1" → the path+query after the method.
                    val pathAndQuery = requestLine.substringAfter(' ', "").substringBefore(' ')
                    runCatching {
                        s.getOutputStream().apply { write(RESPONSE_BYTES); flush() }
                    }
                    AuthRedirect.Received(callbackUri = "http://127.0.0.1:$port$pathAndQuery", redirectUri = redirectUri)
                }
            } finally {
                cancelHandle?.dispose()
                runCatching { server.close() }
            }
        }

    private companion object {
        // Generous enough for the backend's 5-min code TTL; frees the listener if the user wanders off.
        const val ACCEPT_TIMEOUT_MS = 5 * 60 * 1000

        val RESPONSE_BYTES: ByteArray = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Connection: close\r\n\r\n" +
                "<!doctype html><html><body style=\"font-family:sans-serif;text-align:center;padding:3rem\">" +
                "<h2>You're signed in to Deferno.</h2><p>You can close this tab and return to the app.</p>" +
                "</body></html>"
            ).encodeToByteArray()
    }
}

/** Opens [url] in the OS default browser (best-effort; throws if no desktop browser is available). */
private fun desktopBrowse(url: String) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    require(desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) { "no default browser available" }
    desktop.browse(URI(url))
}
