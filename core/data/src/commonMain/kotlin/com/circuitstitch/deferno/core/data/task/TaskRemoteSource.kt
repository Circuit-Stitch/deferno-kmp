package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId

/**
 * The Task-detail + search network port (ADR-0001, #22). It speaks the *domain* [Task] — the wire DTO
 * ugliness is condensed at the network edge by the #18 mappers — so the repository never touches a DTO.
 * The cold list snapshot is **no longer** pulled here: as of ADR-0034 (#226) it migrated to the
 * item-wide `GET /items` ([com.circuitstitch.deferno.core.data.item.ItemSnapshotSource]); this port now
 * carries only the per-item detail and the search overlay:
 *
 * - [fetch] pulls the **full single-item** detail (`/tasks/{id}`) that hydrates a summary on open.
 * - [search] pulls the **global-search result** (`/tasks/search`) — a one-shot, online-only read for
 *   the search overlay (#73), NOT the observed live list (search results are never cached, ADR-0001).
 *
 * Offline-first contract (ADR-0001): a failed background read does not throw. [fetch] returns `null` on
 * failure-or-missing (a no-op-on-null hydrate has no purge to get wrong). [search] reports
 * [TaskSearchResult.Unavailable] — a foreground action whose failure must stay distinct from "no matches".
 */
interface TaskRemoteSource {

    /** The full (hydrated) Task for [id], or `null` when missing / on failure. */
    suspend fun fetch(id: TaskId): Task?

    /**
     * Global search (`GET /tasks/search`, #73): the summary rows matching [query]'s term + filters,
     * or [TaskSearchResult.Unavailable] on a failed pull (search is online-only — there is nothing
     * cached to fall back to, and the search UI shows the failure distinctly).
     */
    suspend fun search(query: TaskSearchQuery): TaskSearchResult
}
