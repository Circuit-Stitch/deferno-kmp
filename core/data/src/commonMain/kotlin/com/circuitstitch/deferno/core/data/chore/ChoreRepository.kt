package com.circuitstitch.deferno.core.data.chore

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import kotlinx.coroutines.flow.Flow

/** The Chore repository the UI depends on (ADR-0001, #71) — reads are local `Flow`s only. */
interface ChoreRepository {
    fun observeChores(): Flow<List<Chore>>
    fun observeChore(id: ChoreId): Flow<Chore?>
}

/** The offline-first [ChoreRepository]: the local store is the single source of truth (ADR-0001). */
class OfflineChoreRepository(
    private val localStore: ChoreLocalStore,
) : ChoreRepository {
    override fun observeChores(): Flow<List<Chore>> = localStore.observeActive()
    override fun observeChore(id: ChoreId): Flow<Chore?> = localStore.observe(id)
}
