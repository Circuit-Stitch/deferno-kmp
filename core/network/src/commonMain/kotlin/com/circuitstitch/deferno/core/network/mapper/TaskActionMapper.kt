package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.network.dto.TaskActionDto
import com.circuitstitch.deferno.core.network.dto.TaskActionKind
import kotlin.time.Instant

/**
 * The DTO→domain mapping for the item-history feed — the "condense at the edge" boundary of ADR-0011.
 * The wire's RFC3339 [TaskActionDto.recordedAt] string becomes a typed [Instant], the wire statuses of
 * [TaskActionKind.StatusChanged] condense through the existing [TaskStatusWire.toWorkingState], and the
 * raw peer id strings pass through (the View resolves their titles). An unrecognised
 * [TaskActionKind.Unknown] stays a bare timestamped [ItemHistoryEvent.Unknown] so the feed neither
 * crashes nor drops an additive server action kind.
 */
fun TaskActionDto.toDomain(): ItemHistoryEvent {
    val at = Instant.parse(recordedAt)
    return when (val k = kind) {
        TaskActionKind.Created -> ItemHistoryEvent.Created(at)
        is TaskActionKind.Updated -> ItemHistoryEvent.Updated(at, k.fields)
        is TaskActionKind.Moved -> ItemHistoryEvent.Moved(at, k.fromParentId, k.toParentId, k.position)
        is TaskActionKind.ParentAssigned -> ItemHistoryEvent.ParentAssigned(at, k.parentId)
        is TaskActionKind.Split -> ItemHistoryEvent.Split(at, k.childId)
        is TaskActionKind.FoldedInto -> ItemHistoryEvent.FoldedInto(at, k.nextTaskId)
        is TaskActionKind.MergedChild -> ItemHistoryEvent.MergedChild(at, k.childId)
        TaskActionKind.MergedIntoParent -> ItemHistoryEvent.MergedIntoParent(at)
        is TaskActionKind.StatusChanged -> ItemHistoryEvent.StatusChanged(at, k.from.toWorkingState(), k.to.toWorkingState())
        TaskActionKind.Unknown -> ItemHistoryEvent.Unknown(at)
    }
}
