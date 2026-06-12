package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The **feedback** write DTOs (#375): the in-app Help → Feedback form's two-step upload —
 * `POST /feedback/attachments/presign` to mint S3 PUT URLs, then `POST /feedback` to submit the
 * comment referencing the uploaded attachment ids. Snake_case wire keys via [SerialName]; decoded by
 * the tolerant [com.circuitstitch.deferno.core.network.DefernoJson].
 */

/** One file's presign request: the metadata the backend signs the PUT URL against. */
@Serializable
data class PresignRequestDto(
    val filename: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)

/** `POST /feedback/attachments/presign` body — the batch of files to presign. */
@Serializable
data class FeedbackPresignBatchRequestDto(
    val files: List<PresignRequestDto>,
)

/**
 * One presigned upload (#375): the new attachment [attachmentId], the [putUrl] to PUT bytes to, and
 * the [headers] the client MUST send byte-exact on that PUT (the SSE-KMS pair + content-type S3 signed
 * into the URL; empty in LocalFs dev mode — a missing header is a guaranteed 403 SignatureDoesNotMatch).
 */
@Serializable
data class PresignResponseDto(
    @SerialName("attachment_id") val attachmentId: String,
    @SerialName("put_url") val putUrl: String,
    @SerialName("expires_at") val expiresAt: String,
    val headers: Map<String, String> = emptyMap(),
)

/** The presign response payload (the envelope `data`): one [PresignResponseDto] per requested file, in order. */
@Serializable
data class FeedbackPresignBatchResponseDto(
    val attachments: List<PresignResponseDto>,
)

/**
 * `POST /feedback` body: a [category]/[subject]/[body] comment plus the [attachmentIds] of the
 * already-uploaded files (bare id strings — the wire also accepts `{id, caption}` objects, unused
 * here). Omitted when there are no attachments (`explicitNulls = false` drops the null).
 */
@Serializable
data class SubmitFeedbackRequestDto(
    val category: String,
    val subject: String,
    val body: String,
    @SerialName("attachment_ids") val attachmentIds: List<String>? = null,
)
