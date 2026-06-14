package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The **attachment** view DTO (`GET /tasks/{id}/attachments`): one file attached to a Task. Snake_case
 * wire keys via [SerialName]; decoded by the tolerant
 * [com.circuitstitch.deferno.core.network.DefernoJson] (the `provider`/`caption_updated_*` fields the
 * wire also carries but v1 ignores pass through). v1 is read-only — the presign/commit upload DTOs are
 * a follow-up; the feedback flow's [PresignRequestDto]/[PresignResponseDto] are the reusable mechanism.
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
