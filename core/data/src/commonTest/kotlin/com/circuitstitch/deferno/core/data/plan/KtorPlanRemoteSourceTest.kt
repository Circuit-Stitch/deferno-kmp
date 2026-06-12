package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
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
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorPlanRemoteSource] (#22) over Ktor's MockEngine (ADR-0006, no real network).
 * Proves it pulls the flat ordered `/tasks/plan` snapshot, keeps just the ids in plan order, and
 * honours the offline-first contract: an error response yields `null` so a failed plan refresh
 * leaves the cached plan intact (ADR-0001).
 */
class KtorPlanRemoteSourceTest {

    private val date = LocalDate.parse("2026-06-06")
    private val tz = "America/Chicago"

    private val planEnvelope = """
        {"version":"0.1","data":[
            {"id":"c","org_slug":"u-e4h2qk","title":"c","date_created":"2026-05-20T16:11:42Z"},
            {"id":"a","org_slug":"u-e4h2qk","title":"a","date_created":"2026-05-20T16:11:42Z"},
            {"id":"b","org_slug":"u-e4h2qk","title":"b","date_created":"2026-05-20T16:11:42Z"}
        ]}
    """.trimIndent()

    @Test
    fun fetchPlanKeepsTheOrderedIds() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorPlanRemoteSource(client { req -> captured = req; respondJson(planEnvelope) })

        val ids = (source.fetchPlan(date, tz) as RemoteSnapshot.Available).value

        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/plan") == true)
        assertEquals(listOf(TaskId("c"), TaskId("a"), TaskId("b")), ids)
    }

    @Test
    fun fetchPlanReportsUnavailableOnFailureSoTheCachedPlanStays() = runTest {
        val source = KtorPlanRemoteSource(client { respond("", HttpStatusCode.Unauthorized) })

        assertEquals(RemoteSnapshot.Unavailable, source.fetchPlan(date, tz))
    }

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
