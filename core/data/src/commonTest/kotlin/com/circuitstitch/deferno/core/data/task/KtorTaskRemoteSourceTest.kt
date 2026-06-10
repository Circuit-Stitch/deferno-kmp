package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorTaskRemoteSource] (#22), driven by Ktor's MockEngine on the JVM-fast path
 * (ADR-0006) — no real network. Proves the two reads hit the right `/tasks` + `/tasks/{id}` paths,
 * map the wire envelope through the #18 DTO->domain mappers (summary -> Summary, detail -> Full),
 * and honour the offline-first contract: an error response yields `emptyList()`/`null` so a failed
 * refresh/hydrate leaves the cache intact rather than wiping it (ADR-0001).
 */
class KtorTaskRemoteSourceTest {

    private val listEnvelope = """
        {"version":"0.1","data":[
            {"id":"a","org_slug":"u-e4h2qk","title":"first","status":"open","sequence":1,
             "date_created":"2026-05-20T16:11:42Z"},
            {"id":"b","org_slug":"u-e4h2qk","title":"second","status":"in-progress","sequence":2,
             "date_created":"2026-05-20T16:11:42Z","deleted_at":"2026-06-01T00:00:00Z"}
        ]}
    """.trimIndent()

    private val detailEnvelope = """
        {"version":"0.1","data":{
            "id":"a","org_slug":"u-e4h2qk","owner_org_id":"org-1","title":"first",
            "status":"open","date_created":"2026-05-20T16:11:42Z",
            "description":"the body","next_task_id":"n-1"
        }}
    """.trimIndent()

    @Test
    fun fetchAllMapsSummariesToDomainTasks() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorTaskRemoteSource(client { req -> captured = req; respondJson(listEnvelope) })

        val tasks = source.fetchAll()

        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks") == true)
        assertEquals(listOf(TaskId("a"), TaskId("b")), tasks.map { it.id })
        assertEquals(WorkingState.Open, tasks[0].workingState)
        assertEquals(HydrationState.Summary, tasks[0].hydration)
        // The tombstone is faithfully carried (the reconcile decides what to do with it).
        assertTrue(tasks[1].isDeleted)
    }

    @Test
    fun fetchMapsDetailToAFullDomainTask() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorTaskRemoteSource(client { req -> captured = req; respondJson(detailEnvelope) })

        val task = source.fetch(TaskId("a"))

        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/a") == true)
        assertEquals(HydrationState.Full, task?.hydration)
        assertEquals("the body", task?.description)
        assertEquals(OrgId("org-1"), task?.ownerOrgId)
        assertEquals(TaskId("n-1"), task?.nextTaskId)
    }

    @Test
    fun fetchAllReturnsEmptyOnFailureSoTheCacheStaysIntact() = runTest {
        val source = KtorTaskRemoteSource(client { respond("", HttpStatusCode.Unauthorized) })

        assertTrue(source.fetchAll().isEmpty())
    }

    @Test
    fun fetchReturnsNullOnFailure() = runTest {
        val source = KtorTaskRemoteSource(client { respond("", HttpStatusCode.InternalServerError) })

        assertNull(source.fetch(TaskId("a")))
    }

    @Test
    fun searchHitsTheSearchPathWithTheTermAndFilterQueryParams() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorTaskRemoteSource(client { req -> captured = req; respondJson(listEnvelope) })

        val outcome = source.search(
            TaskSearchQuery(
                query = "spring",
                statuses = setOf(WorkingState.InProgress),
                labels = setOf("home"),
                fromDate = LocalDate(2026, 6, 1),
                toDate = LocalDate(2026, 6, 30),
            ),
        )

        val params = captured?.url?.parameters
        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/search") == true)
        assertEquals("spring", params?.get("q"))
        assertEquals("in-progress", params?.get("status"))
        assertEquals("home", params?.get("label"))
        // The REST query-param names per the OpenAPI contract (GET /tasks/search) are "from"/"to" —
        // NOT the MCP search_tasks tool's from_date/to_date (#73 follow-up). Sending from_date/to_date
        // made the real backend silently ignore the date range.
        assertEquals("2026-06-01", params?.get("from"))
        assertEquals("2026-06-30", params?.get("to"))
        assertNull(params?.get("from_date"), "the MCP tool param name must not leak onto the REST query")
        assertNull(params?.get("to_date"), "the MCP tool param name must not leak onto the REST query")
        val tasks = (outcome as TaskSearchResult.Success).tasks
        assertEquals(listOf(TaskId("a"), TaskId("b")), tasks.map { it.id })
    }

    @Test
    fun searchReportsUnavailableOnFailure() = runTest {
        // Unlike the background reads, a failed search must stay visible (#73 follow-up): the UI
        // renders "search is unavailable", never a misleading "No matches".
        val source = KtorTaskRemoteSource(client { respond("", HttpStatusCode.InternalServerError) })

        assertEquals(TaskSearchResult.Unavailable, source.search(TaskSearchQuery("query")))
    }

    // --- test helpers ---

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
