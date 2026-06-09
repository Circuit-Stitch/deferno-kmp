package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The Sidecar protocol's error model (ADR-0024). Carried by [SidecarFrame.Failure] — either correlated
 * to a request/stream (`id` set) or connection-level (`id` null). Shaped to mirror core/network's
 * `ApiErrorBody` (a stable `code` + human-readable `message`) so the two error surfaces feel alike.
 *
 * **Privacy (ADR-0009):** [message] is a **non-PII** human-readable summary (like `SpeechError`'s
 * non-PII reasons) — a Helper must never put Transcript text or other private payload in it. [details]
 * is opaque and **redacted from [toString]** so an accidental log can't leak it.
 */
@Serializable
data class SidecarError(
    val code: SidecarErrorCode = SidecarErrorCode.UNKNOWN,
    val message: String = "",
    val details: JsonElement? = null,
) {
    override fun toString(): String = "SidecarError(code=$code, message=$message, details=${redact(details)})"
}

/**
 * The closed set of Sidecar error codes. Decoding is **tolerant** (ADR-0005): a code a newer Helper
 * sends that this client doesn't know coerces to [UNKNOWN] (via `coerceInputValues` in [SidecarJson]),
 * so a forward-compatible Helper never breaks the client.
 */
@Serializable
enum class SidecarErrorCode {
    /** The requested method is not implemented by the bound Helper. */
    @SerialName("unknown_method") UNKNOWN_METHOD,

    /** The request's params were missing or malformed. */
    @SerialName("invalid_params") INVALID_PARAMS,

    /** The peer-auth handshake failed — bad/absent token (the client half, ADR-0024). */
    @SerialName("unauthenticated") UNAUTHENTICATED,

    /** The capability exists but is not available right now (e.g. permission denied, engine busy). */
    @SerialName("unavailable") UNAVAILABLE,

    /** The Helper hit an internal error fulfilling the request. */
    @SerialName("internal") INTERNAL,

    /** A protocol violation — unframeable bytes, an unexpected frame, a correlation-id clash. */
    @SerialName("protocol") PROTOCOL,

    /** Tolerant fallback for a code this client does not recognize (forward-compat). */
    @SerialName("unknown") UNKNOWN,
}
