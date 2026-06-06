package com.circuitstitch.deferno.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The success envelope every Deferno endpoint wraps its payload in (ADR-0005):
 * `{ "version": "0.1", "data": <T> }`. [version] is a breaking-contract counter checked
 * against the supported window ([SupportedApiVersions]) before [data] is handed up; [data]
 * is the per-endpoint payload, decoded with the tolerant reader ([DefernoJson]) so additive
 * backend changes never break parsing.
 */
@Serializable
data class Envelope<out T>(
    val version: String,
    val data: T,
)

/**
 * The error envelope (ADR-0005): `{ "version": "0.1", "error": { "code", "message" } }`.
 *
 * **Not always present:** a `401` returns an EMPTY body (verified 2026-06-06,
 * `contracts/CONTRACT-NOTES.md`), so the reader parses this only when a body is present and
 * otherwise synthesizes [ApiError.Status] from the HTTP status. The `at` field the spec shows
 * was not observed on the wire and is not modelled (the tolerant reader would ignore it anyway).
 */
@Serializable
data class ErrorEnvelope(
    val version: String,
    val error: ApiErrorBody,
)

/** The `error` object inside an [ErrorEnvelope]: a snake_case [code] + a human [message]. */
@Serializable
data class ApiErrorBody(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
)
