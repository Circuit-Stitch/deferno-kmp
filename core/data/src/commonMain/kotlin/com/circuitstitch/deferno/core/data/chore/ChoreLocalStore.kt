package com.circuitstitch.deferno.core.data.chore

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import kotlinx.coroutines.flow.Flow

/** The local source-of-truth port for Chores (ADR-0001, #71) — sibling of `HabitLocalStore`. */
interface ChoreLocalStore {
    fun observeActive(): Flow<List<Chore>>
    fun observe(id: ChoreId): Flow<Chore?>
    /** Every cached id, including tombstones — the `/items` reconcile diffs this against a snapshot (#226). */
    suspend fun allIds(): Set<ChoreId>
    suspend fun get(id: ChoreId): Chore?
    suspend fun upsert(chore: Chore)
    suspend fun delete(id: ChoreId)
    /** Runs [block] atomically (the `/items` reconcile commits + re-emits once at commit, #226). */
    suspend fun transaction(block: suspend (ChoreLocalStore) -> Unit)
}
