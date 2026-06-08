package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceId
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.OccurrenceDto
import com.circuitstitch.deferno.core.network.dto.OccurrenceStatusWire
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Occurrence DTO→domain condense-at-edge mapping (ADR-0011, #71) — the sibling of
 * `RecurringItemMapperTest`. The wire `Occurrence` (parsed via [DefernoJson], proving the [SerialName]
 * wire keys) carries no `kind` (the parent kind is known from the kind-scoped endpoint), so the kind
 * is threaded in; the overloaded wire `OccurrenceStatus` condenses to the clean [OccurrenceState]
 * (`dropped` → `Skipped`, additive tokens → `Scheduled`), and `scheduled_date` parses to a `LocalDate`.
 */
class OccurrenceMapperTest {

    @Test
    fun wireOccurrenceCondensesToTheCleanDomainType() {
        val json = """
            {
              "id": "occ-1",
              "parent_id": "evt-9",
              "scheduled_date": "2026-06-08",
              "complete_by": "2026-06-08T09:00:00Z",
              "status": "done_late"
            }
        """.trimIndent()
        val dto: OccurrenceDto = DefernoJson.decodeFromString(json)

        val occ = dto.toDomain(ItemKind.Event)

        assertEquals(OccurrenceId("occ-1"), occ.id)
        assertEquals("evt-9", occ.definitionId)
        assertEquals(ItemKind.Event, occ.kind)
        assertEquals(LocalDate(2026, 6, 8), occ.date)
        assertEquals(OccurrenceState.DoneLate, occ.state)
    }

    @Test
    fun droppedCondensesToSkipped_andAdditiveTokensDegradeToScheduled() {
        val dropped = OccurrenceDto(id = "o", parentId = "p", scheduledDate = "2026-06-08", status = OccurrenceStatusWire.Dropped)
        assertEquals(OccurrenceState.Skipped, dropped.toDomain(ItemKind.Chore).state)

        // An unmodelled additive status degrades to Scheduled (tolerant reader, ADR-0005) rather than crashing.
        val unknown = OccurrenceDto(id = "o", parentId = "p", scheduledDate = "2026-06-08", status = OccurrenceStatusWire.Unknown)
        assertEquals(OccurrenceState.Scheduled, unknown.toDomain(ItemKind.Habit).state)
    }
}
