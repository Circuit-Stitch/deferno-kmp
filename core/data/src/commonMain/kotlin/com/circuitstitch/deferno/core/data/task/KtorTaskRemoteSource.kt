package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.TaskDetailDto
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.appendPathSegments

/**
 * The production [TaskRemoteSource] over the shared Deferno [HttpClient] (#17/#18, ADR-0001). It
 * condenses each API shape to the domain [Task] at the boundary via the #18 mappers, so
 * [OfflineTaskRepository] never touches a wire DTO:
 *
 * - [fetch]    -> `GET /tasks/{id}` -> `TaskDetailDto` -> [HydrationState.Full][Task].
 *
 * The cold list snapshot moved to `GET /items` (ADR-0034, #226 —
 * [com.circuitstitch.deferno.core.data.item.KtorItemSnapshotSource]); it is no longer fetched here. Global
 * search went **offline** in #311 (a local read over the cache, ADR-0042), so `/tasks/search` is gone too.
 *
 * **Offline-first (ADR-0001).** [fetch] returns `null` on failure-or-missing rather than throwing, so a
 * hydrate that can't reach the server leaves the local cache intact.
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
}

/** Configures a `GET` to [segments] appended onto the client's base URL (CONTRACT-NOTES paths). */
private fun HttpRequestBuilder.get(vararg segments: String) {
    url { appendPathSegments(*segments) }
}
