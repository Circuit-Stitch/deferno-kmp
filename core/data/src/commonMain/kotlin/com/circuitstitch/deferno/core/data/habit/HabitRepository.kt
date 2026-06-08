package com.circuitstitch.deferno.core.data.habit

import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import kotlinx.coroutines.flow.Flow

/**
 * The Habit repository the UI/feature layer depends on (ADR-0001, #71) — the recurring-definition
 * sibling of `TaskRepository`. **Reads are local DB `Flow`s only** ([observeHabits]/[observeHabit]);
 * a Habit created online (ADR-0016) seeds the local store and immediately surfaces on these flows,
 * which is the issue's "the new item appears via the local DB `Flow`" acceptance criterion.
 */
interface HabitRepository {
    /** The live Habit list, observed from the local cache. */
    fun observeHabits(): Flow<List<Habit>>

    /** A single Habit by [id], observed from the local cache. */
    fun observeHabit(id: HabitId): Flow<Habit?>
}

/** The offline-first [HabitRepository]: the local store is the single source of truth (ADR-0001). */
class OfflineHabitRepository(
    private val localStore: HabitLocalStore,
) : HabitRepository {
    override fun observeHabits(): Flow<List<Habit>> = localStore.observeActive()
    override fun observeHabit(id: HabitId): Flow<Habit?> = localStore.observe(id)
}
