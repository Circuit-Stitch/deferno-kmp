package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Occurrence
import com.circuitstitch.deferno.core.model.OccurrenceId
import com.circuitstitch.deferno.core.network.dto.OccurrenceDto
import kotlinx.datetime.LocalDate

/**
 * The DTO→domain mapping for an Occurrence (ADR-0011 "condense at the edge", #71) — the firing-level
 * sibling of `RecurringItemMapper`. The wire ugliness (string id/date, the overloaded
 * `OccurrenceStatus`) stays in `core:network`; the domain [Occurrence] is clean and governed by an
 * [com.circuitstitch.deferno.core.model.OccurrenceState] (how *this* firing went) — never a
 * [com.circuitstitch.deferno.core.model.DefinitionState].
 *
 * The wire payload carries no `kind` (the parent kind is known from the kind-scoped endpoint that
 * returned it — `GET /habits|chores|events/{id}/occurrences`), so [kind] is threaded in by the caller.
 * `status` condenses via the shared [com.circuitstitch.deferno.core.network.dto.OccurrenceStatusWire]
 * → [OccurrenceState] mapping (`dropped` → `Skipped`; additive tokens degrade to `Scheduled`).
 */
fun OccurrenceDto.toDomain(kind: ItemKind): Occurrence = Occurrence(
    id = OccurrenceId(id),
    definitionId = parentId,
    kind = kind,
    date = LocalDate.parse(scheduledDate),
    state = status.toOccurrenceState(),
)
