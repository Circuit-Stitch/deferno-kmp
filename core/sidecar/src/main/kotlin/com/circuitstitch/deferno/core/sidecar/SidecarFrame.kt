package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One message on the Sidecar [[Sidecar protocol]] — a JSON object framed length-prefixed over the
 * socket (ADR-0024). A closed (sealed) polymorphic hierarchy: every frame carries a `"type"`
 * discriminator (see [SidecarJson]). The three traffic shapes the contract must cover all reduce to
 * these frames:
 *
 * - **request/response** — client [Request] → server [Response] (or [Failure] with the same `id`);
 * - **server→client stream** — client [Request] (a stream-opening method) → server [StreamData]* then
 *   [StreamEnd] (or [Failure]); the client may [Cancel] early;
 * - **unsolicited push** — server [Push] with no correlation id.
 *
 * Plus the peer-auth handshake: client [Hello] → server [Welcome] (or [Failure] with `id == null`).
 *
 * **Privacy (ADR-0009):** [Request.params], [Response.result], [StreamData.event] and [Push.payload]
 * are opaque [JsonElement]s the transport never interprets — and **never renders in [toString]**
 * (redacted), so the privacy-critical [[Transcript]] frames can't leak through a stray log. Whether a
 * given payload *is* sensitive is unknowable to the transport, so it treats **all** payloads as such.
 */
@Serializable
sealed interface SidecarFrame {

    /**
     * Client→server handshake: presents the in-band auth [token] (the client half of peer-auth,
     * ADR-0024) and the [protocolVersion] the client speaks. The kernel uid check (`getpeereid`/
     * `SO_PEERCRED`) is the Helper's job; the client additionally verifies socket-path ownership +
     * perms out-of-band before sending this (see `PeerTrust`).
     */
    @Serializable
    @SerialName("hello")
    data class Hello(val token: String, val protocolVersion: Int) : SidecarFrame {
        override fun toString(): String = "Hello(token=<redacted>, protocolVersion=$protocolVersion)"
    }

    /**
     * Server→client handshake ack: the [protocolVersion] the Helper speaks and the [capabilities] it
     * advertises (D4) — the client surfaces these so consumers degrade gracefully against a Helper that
     * lacks a capability rather than calling a method that will only ever fail.
     */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val protocolVersion: Int,
        val capabilities: Set<String> = emptySet(),
    ) : SidecarFrame

    /** Client→server: invoke [method] with opaque [params]. Opens a unary call or a server stream. */
    @Serializable
    @SerialName("request")
    data class Request(val id: Long, val method: String, val params: JsonElement? = null) : SidecarFrame {
        override fun toString(): String = "Request(id=$id, method=$method, params=${redact(params)})"
    }

    /** Server→client: the unary success [result] correlated to [Request.id]. */
    @Serializable
    @SerialName("response")
    data class Response(val id: Long, val result: JsonElement? = null) : SidecarFrame {
        override fun toString(): String = "Response(id=$id, result=${redact(result)})"
    }

    /** Server→client: one item of the server stream opened by [Request.id]. */
    @Serializable
    @SerialName("stream_data")
    data class StreamData(val id: Long, val event: JsonElement) : SidecarFrame {
        override fun toString(): String = "StreamData(id=$id, event=<redacted>)"
    }

    /** Server→client: the server stream opened by [Request.id] completed normally. */
    @Serializable
    @SerialName("stream_end")
    data class StreamEnd(val id: Long) : SidecarFrame

    /** Client→server: stop the server stream opened by [Request.id] (the consumer cancelled). */
    @Serializable
    @SerialName("cancel")
    data class Cancel(val id: Long) : SidecarFrame

    /** Server→client: an unsolicited event on [topic] (e.g. a permission change) with no correlation id. */
    @Serializable
    @SerialName("push")
    data class Push(val topic: String, val payload: JsonElement) : SidecarFrame {
        override fun toString(): String = "Push(topic=$topic, payload=<redacted>)"
    }

    /**
     * Server→client error. [id] correlates it to a [Request] (its response/stream failed); a null [id]
     * is a **connection-level** failure (e.g. a rejected [Hello]).
     */
    @Serializable
    @SerialName("failure")
    data class Failure(val id: Long? = null, val error: SidecarError) : SidecarFrame
}
