package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.flow.Flow

/** A recurring definition addressed kind-neutrally — the key [DefinitionStateSource] reads by. */
data class DefinitionRef(val kind: ItemKind, val definitionId: String)

/**
 * Kind-neutral read of a recurring definition's [DefinitionState] — the one occurrence-state input
 * that lives on the *definition* rather than on the firing.
 *
 * It exists so that no consumer has to fan a `when (kind)` out over the three per-kind stores. There
 * is no such seam at HEAD: Habit/Chore/Event local stores are three separate interfaces keyed by
 * three separate typed ids, and every caller that wanted a definition's light switch had to dispatch
 * on kind itself — exactly the projection-by-kind that the Plan work has just finished removing.
 *
 * Why the reading needs it at all: `archive_habit` states outright that archiving "doesn't touch
 * complete_by/series_id", so a shelved definition keeps a stale cursor forever. Anything deriving
 * Missed without gating on the light switch would report a definition switched off in January as
 * overdue every day since.
 *
 * A Task id, or an id this device has not cached, resolves to `null` — which the resolver reads as
 * [com.circuitstitch.deferno.core.model.OccurrenceState.Unknown], never as Missed.
 */
interface DefinitionStateSource {

    /**
     * Every cached recurring definition's light switch, as one map.
     *
     * Deliberately not a per-row `observe(ref)`: an agenda holds many rows, and one flow of the whole
     * (small) map keeps a consuming component's `combine` inside Kotlin's typed arity ceiling instead
     * of fanning one flow per visible row.
     */
    fun observeAll(): Flow<Map<DefinitionRef, DefinitionState>>

    /** One definition's light switch, read once. `null` for a Task id or an uncached definition. */
    suspend fun get(kind: ItemKind, definitionId: String): DefinitionState?
}
