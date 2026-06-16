package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.TaskDetailDto
import com.circuitstitch.deferno.core.network.dto.TaskSummaryDto
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.mapper.toWireToken
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.http.appendPathSegments

/**
 * The production [TaskRemoteSource] over the shared Deferno [HttpClient] (#17/#18, ADR-0001). It
 * condenses each API shape to the domain [Task] at the boundary via the #18 mappers, so
 * [OfflineTaskRepository] never touches a wire DTO:
 *
 * - [fetch]    -> `GET /tasks/{id}` -> `TaskDetailDto` -> [HydrationState.Full][Task].
 * - [search]   -> `GET /tasks/search?q=…&status=…&label=…&from=…&to=…` -> summary list (#73).
 *
 * The cold list snapshot moved to `GET /items` (ADR-0034, #226 —
 * [com.circuitstitch.deferno.core.data.item.KtorItemSnapshotSource]); it is no longer fetched here.
 *
 * **Offline-first (ADR-0001).** [fetch] returns `null` on failure-or-missing rather than throwing, so a
 * hydrate that can't reach the server leaves the local cache intact. [search] reports
 * [TaskSearchResult.Unavailable] so the foreground search UI can distinguish "couldn't search" from
 * "no matches". The endpoint paths follow the contract's `/tasks/{id}` + `/tasks/search`. The search
 * query params match the OpenAPI REST contract: `q` (≥ 2 chars), `status`, `label`, `from`, `to` —
 * note these date params are `from`/`to`, distinct from the MCP `search_tasks` tool's `from_date`/`to_date`.
 */
class KtorTaskRemoteSource(
    private val client: HttpClient,
) : TaskRemoteSource {

    override suspend fun fetch(id: TaskId): Task? {
        val result = client.requestApi<TaskDetailDto> {
            get("tasks", id.value)
        }
        return when (result) {
            is ApiResult.Success -> result.data.toDomain()
            is ApiResult.Failure -> null
        }
    }

    override suspend fun search(query: TaskSearchQuery): TaskSearchResult {
        val result = client.requestApi<List<TaskSummaryDto>> {
            url { appendPathSegments("tasks", "search") }
            parameter("q", query.query)
            // The contract takes a single status + single label; send the first selected of each (the
            // v1 search UI offers one status-set / label-set, the wire honors one — narrow, not lossy).
            query.statuses.firstOrNull()?.let { parameter("status", it.toWireToken()) }
            query.labels.firstOrNull()?.let { parameter("label", it) }
            // The REST query-param names are "from"/"to" per the OpenAPI contract (GET /tasks/search) —
            // NOT the MCP search_tasks tool's from_date/to_date. Using the tool names made the real
            // backend silently ignore the date range (#73 follow-up).
            query.fromDate?.let { parameter("from", it.toString()) }
            query.toDate?.let { parameter("to", it.toString()) }
        }
        return when (result) {
            is ApiResult.Success -> TaskSearchResult.Success(result.data.map { it.toDomain() })
            is ApiResult.Failure -> TaskSearchResult.Unavailable
        }
    }
}

/** Configures a `GET` to [segments] appended onto the client's base URL (CONTRACT-NOTES paths). */
private fun HttpRequestBuilder.get(vararg segments: String) {
    url { appendPathSegments(*segments) }
}
