package com.circuitstitch.deferno.core.data.task

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
     * Global search (#73): a **one-shot, online-only** pull of the Tasks matching [query] (the search
     * overlay's term + date/status/tag filters + client sort). Deliberately a `suspend` returning a
     * snapshot, **not** an observable `Flow`: search results are a separate read surface and are *not*
     * written into the live [observeTasks] cache (ADR-0001 keeps the observed list local-only — there
     * is no offline search). A failed pull is [TaskSearchResult.Unavailable] so the overlay can show
     * "search is unavailable" instead of a misleading "no matches"; the 2-char minimum guard returns
     * an empty [TaskSearchResult.Success] without a round trip. See [OfflineTaskRepository.search].
     */
    suspend fun search(query: TaskSearchQuery): TaskSearchResult
}

/** Minimum query length the backend `search_tasks` contract enforces (#73). */
const val MIN_SEARCH_QUERY_LENGTH: Int = 2

/** Convenience search by a bare term (no filters, relevance sort). */
suspend fun TaskRepository.search(query: String): TaskSearchResult = search(TaskSearchQuery(query))
