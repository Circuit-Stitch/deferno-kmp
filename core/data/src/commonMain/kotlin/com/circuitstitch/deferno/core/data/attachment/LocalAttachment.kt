package com.circuitstitch.deferno.core.data.attachment

import kotlin.time.Instant

/**
 * An on-device attachment record (#210): the metadata for one stored attachment plus which storage
 * [provider] holds its bytes and the provider-relative [locator] to fetch them. The local-storage
 * counterpart to the domain `Attachment` (which carries a backend-signed `url`); persisted in the
 * per-Account DB and re-opened via [AttachmentBytesStore]. [taskId] is null until the attachment is
 * attached to a Task (#211).
 */
data class LocalAttachment(
    val id: String,
    val taskId: String?,
    val provider: StorageProviderId,
    val locator: String,
    val filename: String,
    val mime: String,
    val size: Long,
    val caption: String?,
    val createdAt: Instant,
)
