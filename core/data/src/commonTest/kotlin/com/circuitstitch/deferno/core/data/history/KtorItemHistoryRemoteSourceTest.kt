package com.circuitstitch.deferno.core.data.history

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.TaskActionKind
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire
import com.circuitstitch.deferno.core.network.dto.toTaskActionKind
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
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorItemHistoryRemoteSource] (ADR-0043, #197) over Ktor's MockEngine (ADR-0006). Proves
 * the refresh hits `/items/{id}/history`, decodes the `actions[]` envelope (each entry carrying its kind
 * as the raw wire element, cracked lazily via [toTaskActionKind]), and is [RemoteSnapshot.Unavailable] on
 * failure so a failed refresh leaves the cache.
 */
class KtorItemHistoryRemoteSourceTest {

    private val historyEnvelope = """
        {"version":"0.1","data":[
            {"kind":"Created","recorded_at":"2026-06-21T12:00:00Z"},
            {"kind":{"StatusChanged":{"from":"open","to":"done"}},"recorded_at":"2026-06-21T12:05:00Z"}
        ]}
    """.trimIndent()

    @Test
    fun fetchHitsTheHistoryPathAndDecodesTheActions() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorItemHistoryRemoteSource(client { req -> captured = req; respondJson(historyEnvelope) })

        val snapshot = source.fetchHistory("t-1")

        assertTrue(captured?.url?.encodedPath?.endsWith("/items/t-1/history") == true)
        val actions = (snapshot as RemoteSnapshot.Available).value
        // The DTO carries `kind` as the raw wire element; decode it lazily (the cache's read path).
        assertEquals(TaskActionKind.Created, actions[0].kind.toTaskActionKind(DefernoJson))
        assertEquals(
            TaskActionKind.StatusChanged(from = openWire(), to = doneWire()),
            actions[1].kind.toTaskActionKind(DefernoJson),
        )
    }

    @Test
    fun fetchIsUnavailableOnFailure() = runTest {
        val source = KtorItemHistoryRemoteSource(client { respond("", HttpStatusCode.InternalServerError) })

        assertEquals(RemoteSnapshot.Unavailable, source.fetchHistory("t-1"))
    }

    private fun openWire() = com.circuitstitch.deferno.core.network.dto.TaskStatusWire.Open
    private fun doneWire() = com.circuitstitch.deferno.core.network.dto.TaskStatusWire.Done

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
