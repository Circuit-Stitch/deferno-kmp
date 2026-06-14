package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The **comment** wire DTOs (`/tasks/{id}/comments` + `/comments/{id}`): the Task detail's Activity
 * thread. Snake_case wire keys via [SerialName]; decoded by the tolerant
 * [com.circuitstitch.deferno.core.network.DefernoJson], so the `body_enc`/`occurrence_date` fields the
 * wire also carries but this v1 client ignores pass through harmlessly. Identity-critical fields
 * ([id], [taskId], [createdBy], [createdAt]) are required; the body is nullable because the wire can
 * carry an encrypted-only comment (no plaintext [body]).
 */
@Serializable
data class CommentDto(
    val id: String,
    @SerialName("task_id") val taskId: String,
    val body: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("is_private") val isPrivate: Boolean = false,
)

/** The `GET /tasks/{id}/comments` payload (the envelope `data`): the thread, oldest-first per the API. */
@Serializable
data class CommentListResponseDto(
    val comments: List<CommentDto> = emptyList(),
)

/** `POST /tasks/{id}/comments` body — a new comment's plaintext [body] and visibility. */
@Serializable
data class CreateCommentPayload(
    val body: String,
    @SerialName("is_private") val isPrivate: Boolean = false,
)

/** `PATCH /comments/{id}` body — edit a comment's [body] (and optionally its visibility). */
@Serializable
data class UpdateCommentPayload(
    val body: String? = null,
    @SerialName("is_private") val isPrivate: Boolean? = null,
)
