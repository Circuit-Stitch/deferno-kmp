package com.circuitstitch.deferno.core.data.habit

import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import kotlinx.coroutines.flow.Flow

/**
 * The local source-of-truth port for Habits (ADR-0001, #71) — the recurring-definition sibling of
 * `TaskLocalStore`. The repository talks to *this*, never the network: UI-facing reads are
 * [observeActive]/[observe] DB `Flow`s, and a create seeds a server-id row via [upsert] so it joins
 * the observe flow with no manual refresh.
 */
interface HabitLocalStore {
    /** The live (non-tombstoned) Habits, observed as a re-emitting `Flow` (ADR-0001 observe-via-Flow). */
    fun observeActive(): Flow<List<Habit>>

    /** A single Habit by [id], or `null` when absent. */
    fun observe(id: HabitId): Flow<Habit?>

    /** Every cached id, including tombstones — the `/items` reconcile diffs this against a snapshot (#226). */
    suspend fun allIds(): Set<HabitId>

    /** The current row for [id] (tombstone included), or `null`. */
    suspend fun get(id: HabitId): Habit?

    /** Inserts or replaces [habit] by its id — the seam a freshly created Habit seeds through. */
    suspend fun upsert(habit: Habit)

    /** Hard-deletes the row [id]. */
    suspend fun delete(id: HabitId)

    /**
     * Runs [block] atomically against this store, so the `/items` reconcile (a batch of upserts +
     * deletes) commits together and re-emits the observe `Flow` once at commit (ADR-0034, #226).
     */
    suspend fun transaction(block: suspend (HabitLocalStore) -> Unit)
}
