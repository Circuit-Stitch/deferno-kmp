package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.network.dto.CommentDto
import kotlin.time.Instant

/**
 * The DTO→domain `Comment` mapping for the Task detail's Activity thread — the "condense at the edge"
 * boundary of ADR-0011. The wire's string ids and RFC3339 timestamp strings become the typed
 * [TaskId]/[UserId] and [Instant]; a blank identity id would fail their `require`, surfacing a
 * malformed comment at the seam rather than deep in the UI.
 */
fun CommentDto.toDomain(): Comment = Comment(
    id = id,
    taskId = TaskId(taskId),
    body = body,
    createdBy = UserId(createdBy),
    createdAt = Instant.parse(createdAt),
    editedAt = editedAt?.let(Instant::parse),
    deletedAt = deletedAt?.let(Instant::parse),
    isPrivate = isPrivate,
)
