package com.circuitstitch.deferno.core.data.occurrence

import com.circuitstitch.deferno.core.model.Occurrence
import com.circuitstitch.deferno.core.model.OccurrenceId
import kotlinx.coroutines.flow.Flow

/**
 * The local source-of-truth port for Occurrences (ADR-0001, #71) — the firing-level sibling of
 * `HabitLocalStore`. The repository talks to *this*, never the network: the UI-facing read is the
 * [observeForDefinition] DB `Flow` (one recurring definition's firings, the calendar-day order), and an
 * occurrence pulled from the kind-scoped endpoint seeds a row via [upsert] so it joins the observe flow
 * with no manual refresh.
 */
interface OccurrenceLocalStore {
    /** The live firings of one definition, observed as a re-emitting `Flow` (ADR-0001 observe-via-Flow). */
    fun observeForDefinition(definitionId: String): Flow<List<Occurrence>>

    /** A single Occurrence by [id], or `null` when absent. */
    fun observe(id: OccurrenceId): Flow<Occurrence?>

    /** The current row for [id], or `null`. */
    suspend fun get(id: OccurrenceId): Occurrence?

    /** Inserts or replaces [occurrence] by its id — the seam a freshly read firing seeds through. */
    suspend fun upsert(occurrence: Occurrence)

    /** Hard-deletes the row [id]. */
    suspend fun delete(id: OccurrenceId)
}
