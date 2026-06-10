package com.circuitstitch.deferno.core.network

/**
 * The outcome of a Deferno API call (issue #17): either the unwrapped, version-checked
 * payload ([Success]) or a typed [ApiError] ([Failure]). A sealed result rather than a thrown
 * exception so the data/domain layer handles every failure mode with an exhaustive `when` and
 * the happy path stays exception-free.
 */
sealed interface ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

/**
 * Maps a [ApiResult.Success] payload through [transform], leaving a [ApiResult.Failure] untouched. The
 * canonical adapter for turning a `requestApi<Dto>` result into a domain type at a remote-source
 * boundary without an exhaustive `when` per call site. [ApiResult.Failure] is `ApiResult<Nothing>`, so a
 * failure passes through unchanged — no re-wrap or re-allocation.
 */
inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

/**
 * Why a call did not yield a payload (issue #17). Four disjoint shapes:
 *
 * - [Endpoint] — the server returned a structured [ErrorEnvelope] (a body was present and
 *   parsed): a snake_case [Endpoint.code] + [Endpoint.message] at an HTTP [Endpoint.status].
 * - [Status] — a non-2xx response with **no parseable error body** (the verified empty `401`,
 *   or any unparseable error body): synthesized from the HTTP [Status.status] alone.
 * - [UnsupportedVersion] — a 2xx whose envelope `version` is outside the supported window
 *   ([SupportedApiVersions]); the payload is withheld rather than risk misparsing it (ADR-0005).
 *   Its [UnsupportedVersion.Kind] is the typed out-of-window signal (#18) — see there.
 * - [Transport] — no usable HTTP response at all: connection / timeout / TLS failure, a
 *   cleartext request the [CleartextGuard] blocked ([CleartextNotPermittedException]), or a
 *   success body that failed to deserialize.
 */
sealed interface ApiError {
    /** A structured server error parsed from an [ErrorEnvelope]. */
    data class Endpoint(val status: Int, val code: String, val message: String) : ApiError

    /** A non-2xx with no parseable [ErrorEnvelope] body — synthesized from the status. */
    data class Status(val status: Int, val message: String) : ApiError

    /**
     * A 2xx whose envelope `version` is outside [SupportedApiVersions] (ADR-0005). The [kind]
     * distinguishes the three out-of-window cases that ADR-0005's policy treats differently (#18),
     * so a caller can react correctly instead of collapsing them into one opaque failure:
     *
     * - [Kind.ForceUpgrade] — server `version` **above** [SupportedApiVersions.MAX]: an unknown
     *   breaking major the client can't parse safely → drive a "force upgrade / update required" gate.
     * - [Kind.Unsupported] — server `version` **below** [SupportedApiVersions.MIN]: too old to map →
     *   degrade/refuse.
     * - [Kind.Unparseable] — `version` is not `major.minor` at all, so it can't be placed against the
     *   window → treat as malformed (ADR-0005: unknown versions are logged so we know to ship an
     *   adapter; there is no logging framework in commonMain, so carrying this typed [kind] *is* the
     *   signal).
     */
    data class UnsupportedVersion(val version: String, val kind: Kind) : ApiError {
        enum class Kind { ForceUpgrade, Unsupported, Unparseable }
    }

    /** No usable response: I/O, TLS, blocked cleartext, or a body that failed to parse. */
    data class Transport(val cause: Throwable) : ApiError
}
