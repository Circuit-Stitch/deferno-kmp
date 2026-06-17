package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The **attachment** view DTO (`GET /tasks/{id}/attachments`, and the `POST` commit's 201 body): one
 * file attached to a Task. Snake_case wire keys via [SerialName]; decoded by the tolerant
 * [com.circuitstitch.deferno.core.network.DefernoJson] (the `provider`/`caption_updated_*` fields the
 * wire also carries but the client ignores pass through).
 */
@Serializable
data class AttachmentViewDto(
    val id: String,
    val filename: String,
    val mime: String,
    val size: Long,
    val url: String,
    val caption: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
)

/**
 * `POST /tasks/{id}/attachments/presign` body — the batch of files to presign, identical in shape to
 * the feedback presign (reuses the generic [PresignRequestDto]). One [PresignResponseDto] comes back
 * per file, in order, under the response envelope's `data.attachments`.
 */
@Serializable
data class AttachmentPresignBatchRequestDto(
    val files: List<PresignRequestDto>,
)

/** The task-attachment presign response payload (the envelope `data`): one entry per requested file, in order. */
@Serializable
data class AttachmentPresignBatchResponseDto(
    val attachments: List<PresignResponseDto>,
)

/**
 * `POST /tasks/{id}/attachments` (commit) body — the presigned uploads to attach, referenced by their
 * `attachment_id`s. The wire also accepts `urls` (link attachments); the client uploads files only, so
 * that field is omitted.
 */
@Serializable
data class CommitAttachmentsPayload(
    val intents: List<AttachmentIntentDto>,
)

/** One commit intent: a previously-presigned [id], with an optional [caption] (unused in v1). */
@Serializable
data class AttachmentIntentDto(
    val id: String,
    val caption: String? = null,
)
