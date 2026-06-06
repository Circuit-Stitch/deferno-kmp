package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.TaskSummaryDto
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.appendPathSegments
import kotlinx.datetime.LocalDate

/**
 * The production [PlanRemoteSource] over the shared Deferno [HttpClient] (#17/#18, ADR-0001).
 * `/tasks/plan` is a flat, ordered list of Task summaries (CONTRACT-NOTES -> Items); the plan only
 * needs the ordering, so this keeps just the ordered ids (`TaskSummaryDto.id`). The summaries
 * themselves are reconciled into the Task cache by the Task repository.
 *
 * Offline-first (ADR-0001): an [ApiResult.Failure] maps to `null`, so a failed plan refresh leaves
 * the cached plan untouched. The `date`/`tz` are passed as query parameters (the exact wire
 * parameter names are not load-bearing for the tests, which drive the fake).
 */
class KtorPlanRemoteSource(
    private val client: HttpClient,
) : PlanRemoteSource {

    override suspend fun fetchPlan(date: LocalDate, tz: String): List<TaskId>? {
        val result = client.requestApi<List<TaskSummaryDto>> {
            url { appendPathSegments("tasks", "plan") }
            parameter("date", date.toString())
            parameter("tz", tz)
        }
        return when (result) {
            is ApiResult.Success -> result.data.map { TaskId(it.id) }
            is ApiResult.Failure -> null
        }
    }
}
