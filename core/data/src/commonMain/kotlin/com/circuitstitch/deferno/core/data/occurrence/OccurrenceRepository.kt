package com.circuitstitch.deferno.core.data.occurrence

import com.circuitstitch.deferno.core.model.Occurrence
import com.circuitstitch.deferno.core.model.OccurrenceId
import kotlinx.coroutines.flow.Flow

/**
 * The Occurrence read repository the UI/feature layer depends on (ADR-0001, #71) — the firing-level
 * sibling of `HabitRepository`. **Reads are local DB `Flow`s only** ([observeForDefinition]/[observe]);
 * an occurrence pulled from the kind-scoped endpoint seeds the local store and immediately surfaces on
 * these flows (the "appears via the local DB `Flow`" acceptance criterion, AC #4).
 */
interface OccurrenceRepository {
    /** The live firings of one recurring definition, observed from the local cache (calendar-day order). */
    fun observeForDefinition(definitionId: String): Flow<List<Occurrence>>

    /** A single Occurrence by [id], observed from the local cache. */
    fun observe(id: OccurrenceId): Flow<Occurrence?>
}

/** The offline-first [OccurrenceRepository]: the local store is the single source of truth (ADR-0001). */
class OfflineOccurrenceRepository(
    private val localStore: OccurrenceLocalStore,
) : OccurrenceRepository {
    override fun observeForDefinition(definitionId: String): Flow<List<Occurrence>> =
        localStore.observeForDefinition(definitionId)

    override fun observe(id: OccurrenceId): Flow<Occurrence?> = localStore.observe(id)
}
