package com.circuitstitch.deferno.core.data.recurring

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * Shared row<->domain codec helpers for the recurring caches (Habit/Chore/Event/Occurrence, #71).
 * Factored out of the three near-identical entity mappings so the encoding rules (the `\n`-joined
 * lists, the boolean<->INTEGER, the **defensive** enum decode that degrades an unrecognised stored
 * token rather than throwing) live in one place — the same rules `TaskEntityMapping.kt` uses, kept
 * symmetric with the adapter-free `.sq` column types.
 */

/** `[]` -> `""`, else the elements joined with `\n` (the list columns never contain newlines). */
fun List<String>.encodeNewlineList(): String = joinToString("\n")

/** `""` -> `[]` (not `[""]`), else the `\n`-split elements. */
fun String.decodeNewlineList(): List<String> = if (isEmpty()) emptyList() else split("\n")

/** Parses a stored RFC3339 timestamp, or `null` when the column is null. */
fun String?.toInstantOrNull(): Instant? = this?.let(Instant::parse)

/** Parses a stored "HH:MM[:SS]" time-of-day, or `null` when absent/unparseable (#348, defensive). */
fun String?.toLocalTimeOrNull(): LocalTime? =
    this?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

/** Defensive decode: an unrecognised stored token degrades to [DefinitionState.Active] (never throws). */
fun String.toDefinitionStateOrDefault(): DefinitionState =
    DefinitionState.entries.firstOrNull { it.name == this } ?: DefinitionState.Active

/** Defensive decode: an unrecognised stored token degrades to [OccurrenceState.Scheduled]. */
fun String.toOccurrenceStateOrDefault(): OccurrenceState =
    OccurrenceState.entries.firstOrNull { it.name == this } ?: OccurrenceState.Scheduled

/** Defensive decode: an unrecognised stored token degrades to [RecurrenceFrequency.Unknown]. */
fun String.toRecurrenceFrequencyOrDefault(): RecurrenceFrequency =
    RecurrenceFrequency.entries.firstOrNull { it.name == this } ?: RecurrenceFrequency.Unknown

/** Defensive decode: an unrecognised stored token degrades to [HydrationState.Summary]. */
fun String.toHydrationStateOrDefault(): HydrationState =
    HydrationState.entries.firstOrNull { it.name == this } ?: HydrationState.Summary
