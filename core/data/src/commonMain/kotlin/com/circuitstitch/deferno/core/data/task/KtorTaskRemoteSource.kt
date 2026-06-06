package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.TaskDetailDto
import com.circuitstitch.deferno.core.network.dto.TaskSummaryDto
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.appendPathSegments

/**
 * The production [TaskRemoteSource] over the shared Deferno [HttpClient] (#17/#18, ADR-0001). It
 * pulls the two API shapes and condenses them to the domain [Task] at the boundary via the #18
 * mappers, so [OfflineTaskRepository] never touches a wire DTO:
 *
 * - [fetchAll] -> `GET /tasks` -> `List<TaskSummaryDto>` -> [HydrationState.Summary][Task] list.
 * - [fetch]    -> `GET /tasks/{id}` -> `TaskDetailDto` -> [HydrationState.Full][Task].
 *
 * **Offline-first (ADR-0001).** Both reads map an [ApiResult.Failure] to nothing
 * (`emptyList()`/`null`) rather than throwing, so a refresh/hydrate that can't reach the server
 * leaves the local cache intact instead of wiping it. The endpoint paths are not load-bearing for
 * the repository tests (those drive the fake); they follow the contract's `/tasks` + `/tasks/{id}`.
 */
class KtorTaskRemoteSource(
    private val client: HttpClient,
) : TaskRemoteSource {

    override suspend fun fetchAll(): List<Task> {
        val result = client.requestApi<List<TaskSummaryDto>> {
            get("tasks")
        }
        return when (result) {
            is ApiResult.Success -> result.data.map { it.toDomain() }
            is ApiResult.Failure -> emptyList()
        }
    }

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
