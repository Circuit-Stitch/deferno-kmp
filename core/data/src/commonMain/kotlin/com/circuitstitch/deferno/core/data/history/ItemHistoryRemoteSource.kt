package com.circuitstitch.deferno.core.data.history

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.asSnapshot
import com.circuitstitch.deferno.core.network.dto.TaskActionDto
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.appendPathSegments

/**
 * The best-effort server read for an Item's server-authored history (ADR-0043, #197) — the enrichment
 * half of the offline-first [ItemHistoryRepository]. `GET /items/{id}/history` is `Envelope<List<TaskAction>>`
 * (append-only, server-derived); a success is [RemoteSnapshot.Available] the repo replaces the cache with,
 * and every failure collapses to [RemoteSnapshot.Unavailable] so the cached history stands (offline-first).
 * v1 renders only on the Task detail, but the endpoint (and this source) is item-scoped.
 */
interface ItemHistoryRemoteSource {

    /** `GET /items/{id}/history` — the full history, or [RemoteSnapshot.Unavailable] on any failure. */
    suspend fun fetchHistory(itemId: String): RemoteSnapshot<List<TaskActionDto>>
}

/** The production [ItemHistoryRemoteSource] over the shared authed Deferno [HttpClient]. */
class KtorItemHistoryRemoteSource(
    private val client: HttpClient,
) : ItemHistoryRemoteSource {

    override suspend fun fetchHistory(itemId: String): RemoteSnapshot<List<TaskActionDto>> =
        client.requestApi<List<TaskActionDto>> {
            url { appendPathSegments("items", itemId, "history") }
        }.asSnapshot()
}
