package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId

/**
 * The network port the Task repository refreshes through (ADR-0001, #22). It speaks the *domain*
 * [Task] — the wire DTO ugliness is condensed at the network edge by the #18 mappers — so the
 * repository never touches a DTO. The reads, matching the API's shapes:
 *
 * - [fetchAll] pulls the **full snapshot** of list summaries (`/tasks`) the reconcile diffs against.
 * - [fetch] pulls the **full single-item** detail (`/tasks/{id}`) that hydrates a summary on open.
 * - [search] pulls the **global-search result** (`/tasks/search`) — a one-shot, online-only read for
 *   the search overlay (#73), NOT the observed live list (search results are never cached, ADR-0001).
 *
 * Offline-first contract (ADR-0001): a failed background read returns `emptyList()`/`null` rather
 * than throwing, so a refresh that can't reach the server leaves the local cache intact instead of
 * wiping it. [search] alone reports failure ([TaskSearchResult.Unavailable]) — it is a foreground
 * action whose failure must stay distinguishable from "no matches".
 */
interface TaskRemoteSource {

    /** The full snapshot of summary Tasks; `emptyList()` on failure (cache left intact). */
    suspend fun fetchAll(): List<Task>

    /** The full (hydrated) Task for [id], or `null` when missing / on failure. */
    suspend fun fetch(id: TaskId): Task?

    /**
     * Global search (`GET /tasks/search`, #73): the summary rows matching [query]'s term + filters,
     * or [TaskSearchResult.Unavailable] on a failed pull (search is online-only — there is nothing
     * cached to fall back to, and the search UI shows the failure distinctly).
     */
    suspend fun search(query: TaskSearchQuery): TaskSearchResult
}
