package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.item.ItemSync
import com.circuitstitch.deferno.core.model.SearchHit
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow

/**
 * The offline-first [TaskRepository] (ADR-0001, #22): the local store is the single source of truth,
 * reads are its `Flow`s, and the network only ever *writes through* the store via [refresh]/[hydrate].
 *
 * **Cold sync ([refresh]) — delegated to [ItemSync].** As of ADR-0034 (#226) the cold snapshot migrated
 * from the legacy task-only `GET /tasks` to the item-wide `GET /items`, so a refresh now reconciles
 * *every* kind (Task/Habit/Chore/Event) into its store — honoring the server-windowed done-visibility
 * window — not just Tasks. That cross-kind reconcile lives in [ItemSync]; this repository just triggers
 * it (the trigger seam stays [TaskRepository.refresh] so its callers are unchanged). The Task reads
 * below are unaffected — they still observe the Task store.
 *
 * **Hydration ([hydrate]).** Opening a Task pulls its full detail (`GET /tasks/{id}`) and upserts it,
 * upgrading the cached row summary -> full; a missing/failed detail is a no-op (the summary stays).
 */
class OfflineTaskRepository(
    private val localStore: TaskLocalStore,
    private val remoteSource: TaskRemoteSource,
    private val itemSync: ItemSync,
) : TaskRepository {

    override fun observeTasks(): Flow<List<Task>> = localStore.observeActive()

    override fun observeTask(id: TaskId): Flow<Task?> = localStore.observe(id)

    override suspend fun refresh() = itemSync.refresh()

    override suspend fun hydrate(id: TaskId) {
        val detail = remoteSource.fetch(id) ?: return
        // The `/tasks/{id}` detail does not carry the server-computed subtree counts (those are an
        // `/items`-snapshot computation, ADR-0034) — so preserve the cached counts the snapshot set,
        // rather than blanking a collapsed tree node's progress badge on detail-open (#226/#227).
        val existing = localStore.get(id)
        localStore.upsert(
            detail.copy(
                descendantDone = detail.descendantDone ?: existing?.descendantDone,
                descendantTotal = detail.descendantTotal ?: existing?.descendantTotal,
            ),
        )
    }

    /**
     * Global search (#73): online-only, one-shot. Guards the contract's 2-char minimum (a too-short
     * term short-circuits to an empty [TaskSearchResult.Success] with no round trip), delegates to
     * [TaskRemoteSource.search] (a failed pull stays [TaskSearchResult.Unavailable] — visible to the
     * foreground search UI, unlike the silent background reads), then applies the **client-side**
     * [SearchSort]. Results are intentionally **not** upserted into the live cache: search is a
     * separate read surface from the observed [observeTasks] list (ADR-0001 keeps that local-only).
     */
    override suspend fun search(query: TaskSearchQuery): TaskSearchResult {
        if (query.query.trim().length < MIN_SEARCH_QUERY_LENGTH) return TaskSearchResult.Success(emptyList())
        return when (val result = remoteSource.search(query)) {
            is TaskSearchResult.Success ->
                TaskSearchResult.Success(result.hits.sortedWith(query.sort.comparator()))
            TaskSearchResult.Unavailable -> result
        }
    }

    private fun SearchSort.comparator(): Comparator<SearchHit> = when (this) {
        SearchSort.Relevance -> Comparator { _, _ -> 0 } // keep the server's ranking
        SearchSort.TitleAsc -> compareBy { it.title.lowercase() }
        // Soonest deadline first; hits without a deadline sort last (nulls last).
        SearchSort.DeadlineAsc -> compareBy(nullsLast()) { it.completeBy }
    }
}
