package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.SearchHit

/**
 * Outcome of the one-shot global search (#73). [Success] carries the (possibly empty) matches as
 * kind-agnostic [SearchHit]s (#231 — the endpoint returns items of every kind, not just Tasks);
 * [Unavailable] is a failed pull — offline, or a 4xx/5xx from `GET /tasks/search`.
 *
 * Search is the one read where failure must stay distinguishable from emptiness: it is an explicit
 * foreground action, so coercing a server error to `emptyList()` (the offline-first posture the
 * *background* pulls rightly keep, ADR-0001) would render a misleading "No matches" when the truth
 * is "couldn't search". The UI shows distinct copy for [Unavailable] instead.
 */
sealed interface TaskSearchResult {
    data class Success(val hits: List<SearchHit>) : TaskSearchResult
    data object Unavailable : TaskSearchResult
}
