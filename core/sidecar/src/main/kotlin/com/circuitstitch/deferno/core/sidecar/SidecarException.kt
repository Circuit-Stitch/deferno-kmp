package com.circuitstitch.deferno.core.sidecar

/**
 * The Sidecar client's failure model (ADR-0024). A sealed hierarchy so callers can branch exhaustively —
 * most importantly, **degrade gracefully on [SidecarUnavailableException]** (no Helper bound) rather
 * than treating an absent native fast path as a hard error (#118 acceptance: connection-absent behavior).
 *
 * **Privacy (ADR-0009):** every message here is built from **metadata only** — method names, correlation
 * ids, topics, error codes, the socket path — and **never** from frame payloads or [[Transcript]] text.
 */
sealed class SidecarException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * No Helper is reachable — the socket path is absent, stale, or refused the connection. The signal a
 * caller catches to **fall back** (e.g. the selector keeps whisper-in-JVM, ADR-0018). Not an error to
 * surface loudly.
 */
class SidecarUnavailableException(message: String, cause: Throwable? = null) : SidecarException(message, cause)

/**
 * The socket path failed the client-half peer-auth trust check — not owned by the current user, or
 * world/group-accessible (ADR-0009/0024). A connection is **refused** rather than risk talking to an
 * impostor on the privacy-critical channel.
 */
class SidecarSecurityException(message: String, cause: Throwable? = null) : SidecarException(message, cause)

/** The connection dropped mid-flight; in-flight requests and open streams fail with this. */
class SidecarConnectionLostException(message: String, cause: Throwable? = null) : SidecarException(message, cause)

/** A protocol violation — an unframeable/oversize message, a malformed or unexpected frame. */
class SidecarProtocolException(message: String, cause: Throwable? = null) : SidecarException(message, cause)

/**
 * The Helper returned a [SidecarFrame.Failure] for a request/stream. The structured [error] is attached;
 * the exception message uses only its code + (non-PII) message, never [SidecarError.details].
 */
class SidecarRequestException(val error: SidecarError) :
    SidecarException("sidecar request failed: code=${error.code}, message=${error.message}")
