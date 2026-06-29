package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalDate

/**
 * A global-search request (#73) — the query term plus the date / status / tag / attachment filters and
 * the sort the search overlay drives. As of #311 (ADR-0042) Search is **offline-first**: this query runs
 * as a local read over the cached Items ([TaskRepository.search]), not a `GET /tasks/search` pull, so it
 * works with no network. It stays distinct from the in-place Tasks-list filter chips — a separate read
 * surface that is never written into the observed live list (ADR-0001 reads are local `Flow`s).
 *
 * @property query the free-text term, matched (case-insensitively) against each item's title +
 *   description. Blank = no text constraint (then a structured filter must be present, else no results).
 * @property statuses the [WorkingState]s to include; empty = no status filter. Task-scoped — recurring
 *   kinds have no [WorkingState], so a non-empty status filter excludes them.
 * @property labels the label tags to require (all of them must be present); empty = no label filter.
 * @property fromDate inclusive lower bound on the deadline date, or `null` for no lower bound.
 * @property toDate inclusive upper bound on the deadline date, or `null` for no upper bound.
 * @property hasAttachment when `true`, keep only items with at least one backend-hosted attachment
 *   (#311). Task-scoped (only Tasks cache an attachment rollup).
 * @property sort how the matched rows are ordered.
 */
data class TaskSearchQuery(
    val query: String,
    val statuses: Set<WorkingState> = emptySet(),
    val labels: Set<String> = emptySet(),
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    val hasAttachment: Boolean = false,
    val sort: SearchSort = SearchSort.Relevance,
)

/**
 * Whether this query has something to run on (#73, #311) — the single source of truth both layers share:
 * [TaskRepository.search] short-circuits to empty when it's `false`, and the overlay's
 * [com.circuitstitch.deferno.feature.tasks.SearchState.canSearch] gates the UI on the same predicate, so
 * the two can't drift. Runnable means a free-text term meeting the [MIN_SEARCH_QUERY_LENGTH] floor, OR any
 * structured filter (status / label / date range / attachment). A blank-or-too-short term with no filter
 * has nothing to match — the overlay's untouched "type to search" state.
 */
fun TaskSearchQuery.hasRunnableConstraint(): Boolean =
    query.trim().length >= MIN_SEARCH_QUERY_LENGTH ||
        statuses.isNotEmpty() || labels.isNotEmpty() ||
        fromDate != null || toDate != null || hasAttachment

/** The ordering applied to search results (#73, #311). */
enum class SearchSort {
    /** Insertion order — the local read's natural cross-kind order (no server ranking offline). */
    Relevance,

    /** Alphabetical by title (A→Z). */
    TitleAsc,

    /** Soonest deadline first; items without a deadline sort last. */
    DeadlineAsc,

    /** Largest total attachment size first (#311); items without attachments (size 0) sort last. */
    AttachmentSizeDesc,
}
