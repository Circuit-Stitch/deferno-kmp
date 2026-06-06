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
 * Why a call did not yield a payload (issue #17). Four disjoint shapes:
 *
 * - [Endpoint] — the server returned a structured [ErrorEnvelope] (a body was present and
 *   parsed): a snake_case [Endpoint.code] + [Endpoint.message] at an HTTP [Endpoint.status].
 * - [Status] — a non-2xx response with **no parseable error body** (the verified empty `401`,
 *   or any unparseable error body): synthesized from the HTTP [Status.status] alone.
 * - [UnsupportedVersion] — a 2xx whose envelope `version` is outside the supported window
 *   ([SupportedApiVersions]); the payload is withheld rather than risk misparsing it (ADR-0005).
 * - [Transport] — no usable HTTP response at all: connection / timeout / TLS failure, a
 *   cleartext request the [CleartextGuard] blocked ([CleartextNotPermittedException]), or a
 *   success body that failed to deserialize.
 */
sealed interface ApiError {
    /** A structured server error parsed from an [ErrorEnvelope]. */
    data class Endpoint(val status: Int, val code: String, val message: String) : ApiError

    /** A non-2xx with no parseable [ErrorEnvelope] body — synthesized from the status. */
    data class Status(val status: Int, val message: String) : ApiError

    /** A 2xx whose envelope `version` is outside [SupportedApiVersions] (ADR-0005). */
    data class UnsupportedVersion(val version: String) : ApiError

    /** No usable response: I/O, TLS, blocked cleartext, or a body that failed to parse. */
    data class Transport(val cause: Throwable) : ApiError
}
