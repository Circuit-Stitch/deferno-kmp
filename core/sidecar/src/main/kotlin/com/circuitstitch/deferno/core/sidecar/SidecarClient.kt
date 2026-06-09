package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path

/**
 * The OS-agnostic JVM half of the native-sidecar substrate (ADR-0024/0025) — the **Sidecar client**.
 * It connects to a bound Helper over a [Transport], performs the peer-auth handshake, and multiplexes
 * the contract's three traffic shapes over the one connection:
 *
 * - [request] — request/response (e.g. [SidecarMethods.QueryPermission]);
 * - [openStream] — a server→client stream (e.g. [SidecarMethods.SubscribeTranscript]);
 * - [pushes] — unsolicited server pushes (e.g. [SidecarTopics.PermissionChanged]).
 *
 * **Connection-absent is normal:** [connect]/[request]/[openStream] throw [SidecarUnavailableException]
 * when no Helper is bound, so callers degrade gracefully (e.g. the selector keeps whisper-in-JVM,
 * ADR-0018) rather than treating an absent fast path as an error.
 *
 * **Privacy (ADR-0009):** payloads are opaque [JsonElement]s; the client never logs them, and its
 * exceptions/diagnostics carry only metadata (method, id, topic, error code) — never [[Transcript]] text.
 */
interface SidecarClient {

    /** The connection lifecycle, observable for UX (e.g. a "native engine connected" indicator). */
    val state: StateFlow<SidecarConnectionState>

    /** Unsolicited server pushes. A hot stream (no replay); collect before triggering a push. */
    val pushes: SharedFlow<SidecarPush>

    /**
     * Connect and perform the handshake (in-band token + capability exchange). Idempotent while
     * [state] is [SidecarConnectionState.Ready].
     *
     * @throws SidecarUnavailableException no Helper bound (degrade gracefully).
     * @throws SidecarSecurityException the path failed peer-trust, or the Helper rejected the token.
     */
    suspend fun connect()

    /** The capabilities the connected Helper advertised in its [SidecarFrame.Welcome] (empty if not connected). */
    fun capabilities(): Set<String>

    /**
     * Request/response: invoke [method] with [params] and await the result (null for a no-content ack).
     * (Re)connects first if needed.
     *
     * @throws SidecarRequestException the Helper returned a [SidecarFrame.Failure].
     * @throws SidecarConnectionLostException the connection dropped before the reply.
     * @throws SidecarUnavailableException no Helper bound.
     */
    suspend fun request(method: String, params: JsonElement? = null): JsonElement?

    /**
     * Open a server→client stream for [method]. The cold [Flow] emits each [SidecarFrame.StreamData]
     * event until the Helper sends [SidecarFrame.StreamEnd] (completes) or a [SidecarFrame.Failure]
     * (throws [SidecarRequestException]); cancelling the collector sends a [SidecarFrame.Cancel] so the
     * Helper stops work (e.g. releases the mic).
     */
    fun openStream(method: String, params: JsonElement? = null): Flow<JsonElement>

    /** Close the connection and release resources. Idempotent. */
    fun close()
}

/** An unsolicited server push (ADR-0024). [payload] is redacted from [toString] (ADR-0009). */
data class SidecarPush(val topic: String, val payload: JsonElement) {
    override fun toString(): String = "SidecarPush(topic=$topic, payload=<redacted>)"
}

/** The Sidecar connection lifecycle. */
sealed interface SidecarConnectionState {
    /** Not connected — never connected, closed, or dropped (re-dialed on the next request). */
    data object Disconnected : SidecarConnectionState

    /** Dialing + handshaking. */
    data object Connecting : SidecarConnectionState

    /** Connected and authenticated; the Helper advertised [capabilities]. */
    data class Ready(val capabilities: Set<String>) : SidecarConnectionState
}

/**
 * Convenience constructor for the production wiring (ADR-0024): a client over an AF_UNIX socket at
 * [path], authenticating with the in-band [token]. (#119 wires this into the DI graph.)
 */
fun unixSocketSidecarClient(path: Path, token: String): SidecarClient =
    DefaultSidecarClient(UnixSocketTransport(path), token)
