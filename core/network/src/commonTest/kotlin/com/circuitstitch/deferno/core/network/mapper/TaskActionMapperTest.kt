package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.TaskActionDto
import com.circuitstitch.deferno.core.network.dto.TaskActionKind
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * DTO→domain condense for the item-history feed (ADR-0011): the wire's RFC3339 timestamp string
 * becomes a typed [Instant], [TaskStatusWire] condenses to [WorkingState], and the raw peer ids pass
 * through unchanged (the View resolves titles / degrades to "another item").
 */
class TaskActionMapperTest {

    private val at = "2026-01-02T03:04:05Z"

    @Test
    fun createdMapsToDomainWithAParsedInstant() {
        val event = TaskActionDto(TaskActionKind.Created, at).toDomain()

        assertEquals(ItemHistoryEvent.Created(Instant.parse(at)), event)
    }

    @Test
    fun statusChangedCondensesWireStatusesToWorkingState() {
        val event = TaskActionDto(
            TaskActionKind.StatusChanged(TaskStatusWire.InProgress, TaskStatusWire.Done), at,
        ).toDomain()

        assertEquals(
            ItemHistoryEvent.StatusChanged(Instant.parse(at), WorkingState.InProgress, WorkingState.Done),
            event,
        )
    }

    @Test
    fun movedPassesItsRawPeerIdsThrough() {
        val event = TaskActionDto(
            TaskActionKind.Moved(fromParentId = null, toParentId = "p-2", position = 3), at,
        ).toDomain()

        assertEquals(ItemHistoryEvent.Moved(Instant.parse(at), null, "p-2", 3), event)
    }

    @Test
    fun anUnknownKindMapsToTheUnknownEvent() {
        val event = TaskActionDto(TaskActionKind.Unknown, at).toDomain()

        assertEquals(ItemHistoryEvent.Unknown(Instant.parse(at)), event)
    }
}
