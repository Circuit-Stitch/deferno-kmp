package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.SearchHit
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow

/**
 * The Task repository the UI/feature layer depends on (ADR-0001, #22). The whole point of the
 * offline-first design lives behind this seam: **reads are local DB `Flow`s only** ([observeTasks]/
 * [observeTask]) — the UI never reads the network — and the network is pulled in by the explicit
 * [refresh]/[hydrate] commands, which write through to the local store the `Flow`s observe. See
 * [OfflineTaskRepository] for the reconcile + hydration semantics.
 */
interface TaskRepository {

    /** The live (non-tombstoned) Task list, observed from the local cache; re-emits on [refresh]. */
    fun observeTasks(): Flow<List<Task>>

    /** A single Task by [id], observed from the local cache; re-emits when [hydrate] enriches it. */
    fun observeTask(id: TaskId): Flow<Task?>

    /**
     * Pulls the full snapshot and reconciles the cache by id (ADR-0001): upsert (honoring
     * tombstones + hydration), remove locally-absent rows — all in one transaction. A failed pull
     * is a no-op (offline-first); see [OfflineTaskRepository.refresh].
     */
    suspend fun refresh()

    /** Opens a Task: pulls its full detail and upgrades the cached row summary -> full (#22). */
    suspend fun hydrate(id: TaskId)

    /**
     * Global search (#73, #311): an **offline, one-shot** local read of the cached Items matching
     * [query] (the overlay's term + date/status/tag/attachment filters + sort), across all kinds.
     * Deliberately a `suspend` returning a snapshot, **not** an observable `Flow`: search results are a
     * separate read surface and are *not* written into the live [observeTasks] cache (ADR-0001 keeps the
     * observed list local-only). It never fails (a local read can't be "unavailable"), so it returns a
     * plain list — empty when nothing matches or when the query has no constraint at all. See
     * [OfflineTaskRepository.search] and ADR-0042.
     */
    suspend fun search(query: TaskSearchQuery): List<SearchHit>
}

/** Minimum free-text query length the search overlay enforces before it will run a text search (#73). */
const val MIN_SEARCH_QUERY_LENGTH: Int = 2

/** Convenience search by a bare term (no filters, relevance sort). */
suspend fun TaskRepository.search(query: String): List<SearchHit> = search(TaskSearchQuery(query))
