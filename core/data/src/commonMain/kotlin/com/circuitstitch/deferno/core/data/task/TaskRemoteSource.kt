package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.RemoteSnapshot
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
 * Offline-first contract (ADR-0001): a failed background read does not throw, so a refresh that can't
 * reach the server leaves the local cache intact instead of wiping it. [fetchAll] carries this in its
 * type — [RemoteSnapshot.Unavailable] on failure, distinct from an [RemoteSnapshot.Available] *empty*
 * snapshot (a genuinely empty server, which the reconcile must honour by purging). [fetch] returns
 * `null` on failure-or-missing (a no-op-on-null hydrate has no purge to get wrong). [search] reports
 * [TaskSearchResult.Unavailable] — a foreground action whose failure must stay distinct from "no matches".
 */
interface TaskRemoteSource {

    /**
     * The full snapshot of summary Tasks as [RemoteSnapshot.Available] (possibly empty), or
     * [RemoteSnapshot.Unavailable] on failure — so the reconcile purges on a genuine empty snapshot
     * but leaves the cache intact when the pull couldn't reach the server.
     */
    suspend fun fetchAll(): RemoteSnapshot<List<Task>>

    /** The full (hydrated) Task for [id], or `null` when missing / on failure. */
    suspend fun fetch(id: TaskId): Task?

    /**
     * Global search (`GET /tasks/search`, #73): the summary rows matching [query]'s term + filters,
     * or [TaskSearchResult.Unavailable] on a failed pull (search is online-only — there is nothing
     * cached to fall back to, and the search UI shows the failure distinctly).
     */
    suspend fun search(query: TaskSearchQuery): TaskSearchResult
}
