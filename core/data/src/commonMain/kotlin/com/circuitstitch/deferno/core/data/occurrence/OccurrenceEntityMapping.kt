package com.circuitstitch.deferno.core.data.occurrence

import com.circuitstitch.deferno.core.data.recurring.toOccurrenceStateOrDefault
import com.circuitstitch.deferno.core.database.sql.OccurrenceEntity
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Occurrence
import com.circuitstitch.deferno.core.model.OccurrenceId
import kotlinx.datetime.LocalDate

/**
 * The row<->domain conversion for the Occurrence cache (ADR-0001, #71) — the firing-level sibling of
 * `HabitEntityMapping.kt`. core:database keeps `occurrenceEntity` adapter-free, so the rich-type
 * translation (the [com.circuitstitch.deferno.core.model.OccurrenceState], the [LocalDate], the
 * [ItemKind], the [OccurrenceId] value class) lives here. The stored [ItemKind]/state tokens decode
 * **defensively** — an unrecognised token degrades rather than throwing (the same rule the recurring
 * caches use via `RecurringEntityCodec.kt`), keeping a row usable across additive enum growth.
 */
fun OccurrenceEntity.toDomain(): Occurrence = Occurrence(
    id = OccurrenceId(id),
    definitionId = definition_id,
    kind = kind.toItemKindOrDefault(),
    date = LocalDate.parse(occurrence_date),
    state = occurrence_state.toOccurrenceStateOrDefault(),
)

fun Occurrence.toEntity(): OccurrenceEntity = OccurrenceEntity(
    id = id.value,
    definition_id = definitionId,
    kind = kind.name,
    occurrence_date = date.toString(),
    occurrence_state = state.name,
)

/** Defensive decode: an unrecognised stored kind token degrades to [ItemKind.Task] (never throws). */
internal fun String.toItemKindOrDefault(): ItemKind =
    ItemKind.entries.firstOrNull { it.name == this } ?: ItemKind.Task
