package com.circuitstitch.deferno.core.data.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL
import platform.posix.AF_INET
import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.coroutines.coroutineContext

/**
 * macOS [BrowserAuthenticator] (ADR-0026/0029, #189): the desktop twin of the JVM
 * [LoopbackBrowserAuthenticator]. It binds a loopback HTTP listener on `127.0.0.1:{ephemeral}`, opens the
 * authorize URL in the user's real default browser ([NSWorkspace]), and blocks for the one inbound GET —
 * the backend's redirect to `http://127.0.0.1:{port}/callback?code=…&state=…` (RFC 8252 §7.3) — replying
 * with a tiny "you can close this tab" page and returning the captured request URI.
 *
 * **Loopback, not a `com.circuitstitch.deferno://` custom scheme.** A custom-scheme redirect routes
 * through LaunchServices on macOS: it pops Safari's *"allow this site to open the app?"* prompt and can
 * launch a SECOND app process instead of returning to the running one (so the in-flight sign-in never
 * sees the redirect). Observed in testing (#189). The loopback request never leaves the browser↔localhost
 * socket, so neither happens, and the browser shows a real page. (iOS keeps `ASWebAuthenticationSession`,
 * which captures in-process; a desktop wants its real browser — see the ADR-0026 macOS amendment.)
 *
 * The blocking wait honours coroutine cancellation: the listener fd is closed when the calling job
 * completes (unblocking [poll]/[accept]), and a 5-minute poll timeout frees it if the user wanders off.
 * The shared "Need to try again?" affordance cancels this leg to restart it.
 */
@OptIn(ExperimentalForeignApi::class)
class MacBrowserAuthenticator : BrowserAuthenticator {

    override val registrationRedirectUri: String = "http://127.0.0.1/callback"

    override suspend fun authenticate(buildAuthorizeUrl: (redirectUri: String) -> String): AuthRedirect =
        withContext(Dispatchers.Default) {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            if (fd < 0) return@withContext AuthRedirect.Failed("could not create loopback socket")

            val port = bindEphemeralLoopback(fd)
            if (port == null) {
                close(fd)
                return@withContext AuthRedirect.Failed("could not bind loopback listener")
            }
            val redirectUri = "http://127.0.0.1:$port/callback"

            // Closing the listener when the sign-in coroutine completes/cancels unblocks the poll below.
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { close(fd) }
            try {
                val url = NSURL.URLWithString(buildAuthorizeUrl(redirectUri))
                    ?: return@withContext AuthRedirect.Failed("invalid authorize URL")
                if (!NSWorkspace.sharedWorkspace.openURL(url)) {
                    return@withContext AuthRedirect.Failed("could not open the browser")
                }

                // Wait (≤5 min) for the redirect; 0 = the user abandoned it, <0 = listener closed (cancel).
                val ready = memScoped {
                    val pfd = alloc<pollfd>()
                    pfd.fd = fd
                    pfd.events = POLLIN.toShort()
                    poll(pfd.ptr, 1.convert(), ACCEPT_TIMEOUT_MS)
                }
                if (ready <= 0) return@withContext AuthRedirect.Cancelled

                val conn = accept(fd, null, null)
                if (conn < 0) return@withContext AuthRedirect.Cancelled
                try {
                    val requestLine = readRequestLine(conn)
                        ?: return@withContext AuthRedirect.Failed("empty redirect request")
                    sendCloseTabPage(conn)
                    // "GET /callback?code=…&state=… HTTP/1.1" → the path+query between the two spaces.
                    val pathAndQuery = requestLine.substringAfter(' ', "").substringBefore(' ')
                    if (pathAndQuery.isEmpty()) {
                        AuthRedirect.Failed("malformed redirect request")
                    } else {
                        AuthRedirect.Received("http://127.0.0.1:$port$pathAndQuery", redirectUri)
                    }
                } finally {
                    close(conn)
                }
            } finally {
                cancelHandle?.dispose()
                close(fd)
            }
        }

    private companion object {
        // Matches the backend's 5-min code TTL; frees the listener if the user wanders off (poll ms).
        const val ACCEPT_TIMEOUT_MS = 5 * 60 * 1000
    }
}

/**
 * Bind [fd] to an ephemeral port on 127.0.0.1 and start listening; returns the assigned host port, or
 * null if bind/listen/getsockname fails. Apple targets are little-endian, so the loopback address and the
 * port byte-swap (network→host order) are inlined rather than pulling in htonl/ntohs.
 */
@OptIn(ExperimentalForeignApi::class)
private fun bindEphemeralLoopback(fd: Int): Int? = memScoped {
    val addr = alloc<sockaddr_in>()
    addr.sin_len = sizeOf<sockaddr_in>().convert()
    addr.sin_family = AF_INET.convert()
    addr.sin_port = 0u.convert()                 // ephemeral; htons(0) == 0
    addr.sin_addr.s_addr = 0x0100007fu           // 127.0.0.1 in network byte order (little-endian host)
    if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) return null
    if (listen(fd, 1) != 0) return null

    val bound = alloc<sockaddr_in>()
    val len = alloc<UIntVar>()                   // socklen_t (== unsigned int on Darwin)
    len.value = sizeOf<sockaddr_in>().convert()
    if (getsockname(fd, bound.ptr.reinterpret(), len.ptr) != 0) return null
    val netPort = bound.sin_port.toInt() and 0xffff
    ((netPort and 0xff) shl 8) or ((netPort shr 8) and 0xff)   // ntohs on a little-endian host
}

/** Read the first request line (the "GET …" line) from [conn]; null if the socket gave nothing. */
@OptIn(ExperimentalForeignApi::class)
private fun readRequestLine(conn: Int): String? {
    val buffer = ByteArray(8192)
    val received = buffer.usePinned { recv(conn, it.addressOf(0), buffer.size.convert(), 0) }
    if (received <= 0) return null
    val text = buffer.decodeToString(0, received.toInt())
    return text.substringBefore("\r\n").substringBefore("\n").ifEmpty { null }
}

/** Reply with a minimal "you can close this tab" page so the browser shows something friendly. */
@OptIn(ExperimentalForeignApi::class)
private fun sendCloseTabPage(conn: Int) {
    val response = (
        "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Connection: close\r\n\r\n" +
            "<!doctype html><html><body style=\"font-family:sans-serif;text-align:center;padding:3rem\">" +
            "<h2>You're signed in to Deferno.</h2><p>You can close this tab and return to the app.</p>" +
            "</body></html>"
        ).encodeToByteArray()
    response.usePinned { send(conn, it.addressOf(0), response.size.convert(), 0) }
}
