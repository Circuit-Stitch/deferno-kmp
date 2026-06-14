package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.network.dto.AttachmentViewDto
import kotlin.time.Instant

/**
 * The DTO→domain `Attachment` mapping for the Task detail's attachment list — the "condense at the
 * edge" boundary of ADR-0011. The wire's `created_by` string becomes a typed [UserId] and the RFC3339
 * `created_at` an [Instant].
 */
fun AttachmentViewDto.toDomain(): Attachment = Attachment(
    id = id,
    filename = filename,
    mime = mime,
    size = size,
    url = url,
    caption = caption,
    createdBy = UserId(createdBy),
    createdAt = Instant.parse(createdAt),
)
