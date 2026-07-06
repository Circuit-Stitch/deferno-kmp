package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.TaskActionKind
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * kind→domain condense for the item-history feed (ADR-0011): the caller's parsed RFC3339 [Instant] rides
 * through, [TaskStatusWire] condenses to [WorkingState], and the raw peer ids pass through unchanged (the
 * View resolves titles / degrades to "another item").
 */
class TaskActionMapperTest {

    private val at = Instant.parse("2026-01-02T03:04:05Z")

    @Test
    fun createdMapsToDomainWithTheGivenInstant() {
        val event = TaskActionKind.Created.toDomain(at)

        assertEquals(ItemHistoryEvent.Created(at), event)
    }

    @Test
    fun statusChangedCondensesWireStatusesToWorkingState() {
        val event = TaskActionKind.StatusChanged(TaskStatusWire.InProgress, TaskStatusWire.Done).toDomain(at)

        assertEquals(
            ItemHistoryEvent.StatusChanged(at, WorkingState.InProgress, WorkingState.Done),
            event,
        )
    }

    @Test
    fun movedPassesItsRawPeerIdsThrough() {
        val event = TaskActionKind.Moved(fromParentId = null, toParentId = "p-2", position = 3).toDomain(at)

        assertEquals(ItemHistoryEvent.Moved(at, null, "p-2", 3), event)
    }

    @Test
    fun anUnknownKindMapsToTheUnknownEvent() {
        val event = TaskActionKind.Unknown.toDomain(at)

        assertEquals(ItemHistoryEvent.Unknown(at), event)
    }
}
