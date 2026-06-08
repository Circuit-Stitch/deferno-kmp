package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorItemRemoteSource] (#71, ADR-0016) over Ktor's MockEngine (ADR-0006 JVM-fast path).
 * Proves the four creates POST to the right `/tasks|/habits|/chores|/events` path, condense the full
 * single-item `Envelope<item>` response to the domain entity, and that a 4xx maps to [ApiResult.Failure]
 * (so the writer can surface the gentle error). Plus convert → `POST /items/{id}/convert`.
 */
class KtorItemRemoteSourceTest {

    @Test
    fun createTaskPostsToTasksAndMapsTheFullItemResponse() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorItemRemoteSource(
            client { req ->
                captured = req
                respondJson("""{"version":"0.1","data":{"id":"server-1","org_slug":"u-e4h2qk","title":"buy milk","status":"open","date_created":"2026-05-20T16:11:42Z","owner_org_id":"org-1","description":"body"}}""")
            },
        )

        val result = source.createTask(CreateTaskPayload(title = "buy milk"))

        assertEquals(HttpMethod.Post, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks") == true)
        val task = assertIs<ApiResult.Success<*>>(result).data
        assertEquals(TaskId("server-1"), (task as com.circuitstitch.deferno.core.model.Task).id)
    }

    @Test
    fun createHabitPostsToHabitsAndCondensesRecurrence() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorItemRemoteSource(
            client { req ->
                captured = req
                respondJson("""{"version":"0.1","data":{"id":"h-1","org_slug":"u-e4h2qk","title":"stretch","status":"active","date_created":"2026-05-04T01:53:05Z","recurrence":{"type":"daily"}}}""")
            },
        )

        val result = source.createHabit(CreateHabitPayload(title = "stretch", recurrence = RecurrenceDto("daily")))

        assertTrue(captured?.url?.encodedPath?.endsWith("/habits") == true)
        val habit = assertIs<ApiResult.Success<*>>(result).data as com.circuitstitch.deferno.core.model.Habit
        assertEquals(HabitId("h-1"), habit.id)
        assertEquals(DefinitionState.Active, habit.definitionState)
        assertEquals(RecurrenceFrequency.Daily, habit.recurrence?.frequency)
    }

    @Test
    fun createChoreAndEventHitTheirPaths() = runTest {
        var chorePath: String? = null
        val choreSource = KtorItemRemoteSource(
            client { req ->
                chorePath = req.url.encodedPath
                respondJson("""{"version":"0.1","data":{"id":"c-1","org_slug":"u-e4h2qk","title":"trash","status":"active","date_created":"2026-05-12T19:52:01Z","cadence_mode":"rolling","recurrence":{"type":"weekly","days":["Tue"]}}}""")
            },
        )
        choreSource.createChore(CreateChorePayload(title = "trash", recurrence = RecurrenceDto("weekly", days = listOf("Tue"))))
        assertTrue(chorePath?.endsWith("/chores") == true)

        var eventPath: String? = null
        val eventSource = KtorItemRemoteSource(
            client { req ->
                eventPath = req.url.encodedPath
                respondJson("""{"version":"0.1","data":{"id":"e-1","org_slug":"u-e4h2qk","title":"standup","status":"active","date_created":"2026-05-02T15:00:34Z","complete_by":"2026-04-18T16:00:00Z","end_time":"2026-04-18T17:30:00Z","all_day":false}}""")
            },
        )
        eventSource.createEvent(CreateEventPayload(title = "standup", completeBy = "2026-04-18T16:00:00Z"))
        assertTrue(eventPath?.endsWith("/events") == true)
    }

    @Test
    fun aServerRejectionMapsToFailure() = runTest {
        val source = KtorItemRemoteSource(client { respond("", HttpStatusCode.UnprocessableEntity) })

        val result = source.createTask(CreateTaskPayload(title = ""))

        assertIs<ApiResult.Failure>(result)
    }

    @Test
    fun convertPostsToItemsConvertAndMapsTheNewKind() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorItemRemoteSource(
            client { req ->
                captured = req
                respondJson("""{"version":"0.1","data":{"type":"chore","id":"item-1","org_slug":"u-e4h2qk","title":"trash","status":"active","date_created":"2026-05-12T19:52:01Z","cadence_mode":"rolling","recurrence":{"type":"weekly","days":["Tue"]}}}""")
            },
        )

        val result = source.convert("item-1", ConvertItemPayload(type = "chore", recurrence = RecurrenceDto("weekly")))

        assertEquals(HttpMethod.Post, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/items/item-1/convert") == true)
        val converted = assertIs<ApiResult.Success<ConvertedItem>>(result).data
        assertEquals(ItemKind.Chore, converted.kind)
    }

    // --- helpers ---

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { url("https://api.example.test/") }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}
