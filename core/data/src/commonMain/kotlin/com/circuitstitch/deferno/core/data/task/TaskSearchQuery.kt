package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalDate

/**
 * A global-search request (#73, `GET /tasks/search`) — the query term plus the date / status / tag
 * filters and the client sort the search overlay drives. Distinct from the in-place Tasks-list filter
 * chips: this is the **one-shot, online-only** search surface, not the offline observed list (ADR-0001
 * reads are local `Flow`s — search results are never written into the live cache).
 *
 * The backend `GET /tasks/search` contract takes `q` (min 2 chars), an optional `status`, a single
 * `label`, and a `from`/`to` date range. [TaskRepository.search] honors the same shape; the sort is
 * applied client-side ([SearchSort]). (The MCP `search_tasks` tool names the date range
 * `from_date`/`to_date`, but the REST query params are `from`/`to` — see [KtorTaskRemoteSource].)
 *
 * @property query the free-text term (title + description); the search guard requires ≥ 2 chars.
 * @property statuses the [WorkingState]s to include; empty = no status filter (all states).
 * @property labels the label tags to require; empty = no label filter. The wire takes one `label`, so
 *   the remote source sends the first (the UI offers a single label chip set in v1).
 * @property fromDate inclusive lower bound on the deadline date, or `null` for no lower bound.
 * @property toDate inclusive upper bound on the deadline date, or `null` for no upper bound.
 * @property sort how the returned rows are ordered client-side.
 */
data class TaskSearchQuery(
    val query: String,
    val statuses: Set<WorkingState> = emptySet(),
    val labels: Set<String> = emptySet(),
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    val sort: SearchSort = SearchSort.Relevance,
)

/**
 * The client-side ordering applied to search results (#73). The backend returns relevance-ranked rows;
 * the sort control lets the user re-order them without a second round trip.
 */
enum class SearchSort {
    /** The server's own ranking, left untouched. */
    Relevance,

    /** Alphabetical by title (A→Z). */
    TitleAsc,

    /** Soonest deadline first; tasks without a deadline sort last. */
    DeadlineAsc,
}
