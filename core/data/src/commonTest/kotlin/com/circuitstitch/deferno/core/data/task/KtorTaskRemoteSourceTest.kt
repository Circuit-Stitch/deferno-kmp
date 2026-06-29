package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.TaskId
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorTaskRemoteSource] (#22), driven by Ktor's MockEngine on the JVM-fast path
 * (ADR-0006) — no real network. Proves the detail read hits the right `/tasks/{id}` path and maps the
 * wire envelope through the #18 DTO->domain mappers, and honours the offline-first contract: an error
 * response yields `null` so a failed hydrate leaves the cache intact. (The cold list snapshot moved to
 * `GET /items` — `KtorItemSnapshotSourceTest` — #226; global search went offline — #311.)
 */
class KtorTaskRemoteSourceTest {

    private val detailEnvelope = """
        {"version":"0.1","data":{
            "id":"a","org_slug":"u-e4h2qk","owner_org_id":"org-1","title":"first",
            "status":"open","date_created":"2026-05-20T16:11:42Z",
            "description":"the body","next_task_id":"n-1"
        }}
    """.trimIndent()

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
    fun fetchReturnsNullOnFailure() = runTest {
        val source = KtorTaskRemoteSource(client { respond("", HttpStatusCode.InternalServerError) })

        assertNull(source.fetch(TaskId("a")))
    }

    // Global search went offline in #311 (a local read over the cache, ADR-0042) — it is proved in
    // OfflineTaskRepositoryTest now, and KtorTaskRemoteSource no longer carries `/tasks/search`.

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
