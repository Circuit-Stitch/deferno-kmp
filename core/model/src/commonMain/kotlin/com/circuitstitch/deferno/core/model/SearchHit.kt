package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * One global-search match (#73, #231) — **kind-agnostic**. The search endpoint returns items of every
 * [ItemKind] (the wire `type` discriminant is required on each row), so a hit carries its real [kind]
 * rather than being force-fit to a [Task] the way the v1 client did. This is the small shared shape a
 * search result row needs: the [kind] dot/badge, the [title] (with match highlight applied in the View),
 * the done/active [isTerminal] de-emphasis, the server-derived [blocked] flag (#292 — a blocked item is
 * still returned but flagged so it isn't mistaken for actionable), and the [completeBy]/[deadlineTimeOfDay]
 * due for the trailing date badge. [id] is the raw item UUID (a Task hit opens via `TaskId(id)`); it is not
 * kind-typed because the result set spans kinds.
 *
 * It deliberately omits ancestry/breadcrumb and grove — neither is on the search wire today (a result
 * carries only a single `parent_id`), so surfacing the design's "path to the tree" needs a new server
 * field, not just a client change.
 */
data class SearchHit(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val isTerminal: Boolean = false,
    val blocked: Boolean = false,
    val completeBy: Instant? = null,
    val deadlineTimeOfDay: LocalTime? = null,
    val ref: String? = null,
    // Backend-hosted attachment rollup (#311), carried so a result row can show a "N files · size" badge and
    // the offline attachment-size sort has a value to order by. Task-only today (recurring hits carry 0).
    val attachmentCount: Int = 0,
    val attachmentTotalSize: Long = 0,
) {
    /** Whether this hit has at least one backend-hosted attachment (#311). */
    val hasAttachment: Boolean get() = attachmentCount > 0
}
