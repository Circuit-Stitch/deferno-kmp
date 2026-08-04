package com.circuitstitch.deferno.core.data.occurrence

import com.circuitstitch.deferno.core.data.recurring.toInstantOrNull
import com.circuitstitch.deferno.core.database.sql.OccurrenceFactEntity
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import kotlinx.datetime.LocalDate

/**
 * The row<->domain conversion for the occurrence **fact** cache (ADR-0001, ADR-0053 decision 4, #390).
 * `occurrenceFactEntity` is adapter-free by design (#21), so the rich-type translation — the
 * [OccurrenceResolution], the [LocalDate], the two `kotlin.time.Instant`s, the [ItemKind] — lives here.
 *
 * Both stored enum tokens are the DOMAIN enum's `.name`, never a wire token: these columns are a local
 * persistence key, the same rule `OccurrenceTargets` states for the outbox target. Neither decode
 * throws, but the two degrade **differently**, on purpose:
 *
 * - An unrecognised **kind** drops the row ([toDomainOrNull] returns `null`). `kind` is the leading
 *   component of the primary key and names which definition table the firing belongs to; coercing an
 *   unknown token to [ItemKind.Task] — as the retired `occurrenceEntity` mapping did — would file a
 *   firing under the wrong definition entirely. That is the mis-resolution #385 removed from the Plan,
 *   and the `dailyPlanEntry` read already prefers "unresolvable" to "wrong".
 * - An unrecognised **resolution** degrades to [OccurrenceResolution.Scheduled], the least-committal
 *   stored value: a row exists for this firing but records no progress this build can name. The row is
 *   kept because its existence is itself evidence — dropping it would make the day look unrecorded,
 *   which reads as Missed for a past date inside coverage. Reporting "no progress" is the honest
 *   degradation; inventing a completion or an accusation is not.
 */
internal fun OccurrenceFactEntity.toDomainOrNull(): OccurrenceFact? {
    val itemKind = ItemKind.entries.firstOrNull { it.name == kind } ?: return null
    return OccurrenceFact(
        kind = itemKind,
        definitionId = definition_id,
        date = LocalDate.parse(occurrence_date),
        resolution = resolution.toOccurrenceResolutionOrDefault(),
        doneAt = done_at.toInstantOrNull(),
        completeBy = complete_by.toInstantOrNull(),
    )
}

internal fun OccurrenceFact.toEntity(): OccurrenceFactEntity = OccurrenceFactEntity(
    kind = kind.name,
    definition_id = definitionId,
    occurrence_date = date.toString(),
    resolution = resolution.name,
    done_at = doneAt?.toString(),
    complete_by = completeBy?.toString(),
)

/** Defensive decode: an unrecognised stored token degrades to [OccurrenceResolution.Scheduled]. */
private fun String.toOccurrenceResolutionOrDefault(): OccurrenceResolution =
    OccurrenceResolution.entries.firstOrNull { it.name == this } ?: OccurrenceResolution.Scheduled
