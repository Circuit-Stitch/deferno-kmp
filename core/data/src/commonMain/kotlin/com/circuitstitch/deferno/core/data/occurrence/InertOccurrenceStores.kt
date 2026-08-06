package com.circuitstitch.deferno.core.data.occurrence

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.OccurrenceFact
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate

/**
 * The no-op occurrence stores — every read empty, every write dropped.
 *
 * **The emptiness is the correct default, and it is not the same as "no firings".** An empty
 * [[Occurrence coverage]] store says *"this device has looked at nothing"*, so `resolveOccurrenceState`
 * reads every firing as **Unknown**. A store that instead reported full coverage and no facts would
 * derive **Missed** for every past day — which is the accusatory reading ADR-0053 exists to prevent a
 * client from inventing. Defaulting to ignorance is the honest posture for a host with no data behind
 * it.
 *
 * These live here rather than in a host module because three consumers now need them — the shell's
 * default `AccountSession`, the Tasks feature's defaulted constructor params (#383), and previews —
 * and a per-host copy is a per-host chance to get the above backwards.
 */
object InertOccurrenceFactLocalStore : OccurrenceFactLocalStore {
    override fun observeOn(date: LocalDate) = flowOf(emptyList<OccurrenceFact>())
    override fun observeInRange(kind: ItemKind, definitionId: String, from: LocalDate, to: LocalDate) =
        flowOf(emptyList<OccurrenceFact>())

    override fun observe(kind: ItemKind, definitionId: String, date: LocalDate) = flowOf<OccurrenceFact?>(null)
    override suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate): OccurrenceFact? = null
    override suspend fun upsert(fact: OccurrenceFact) {}
    override suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate) {}
    override suspend fun replaceRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
        facts: List<OccurrenceFact>,
    ) {}

    override suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit) = block(this)
}

/** The no-op coverage store. See [InertOccurrenceFactLocalStore] for why empty is the right default. */
object InertOccurrenceCoverageLocalStore : OccurrenceCoverageLocalStore {
    override fun observeCovering(date: LocalDate) = flowOf(emptyList<OccurrenceCoverage>())
    override suspend fun get(kind: ItemKind, definitionId: String) = emptyList<OccurrenceCoverage>()
    override suspend fun record(coverage: OccurrenceCoverage) {}
    override suspend fun clear(kind: ItemKind, definitionId: String) {}
}
