package com.circuitstitch.deferno.core.data.comment

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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorCommentRemoteSource] (ADR-0043, #197) over Ktor's MockEngine on the JVM-fast path
 * (ADR-0006). Proves the refresh read hits `/tasks/{id}/comments`, condenses the wire envelope through
 * the #18 DTO->domain mapper, drops server-tombstoned rows, and honours the offline-first contract: any
 * failure is [RemoteSnapshot.Unavailable] so a failed refresh leaves the cache intact.
 */
class KtorCommentRemoteSourceTest {

    private val threadEnvelope = """
        {"version":"0.1","data":{"comments":[
            {"id":"c-1","task_id":"t-1","body":"first","created_by":"u-1","created_at":"2026-06-21T12:00:00Z"},
            {"id":"c-2","task_id":"t-1","body":"gone","created_by":"u-2","created_at":"2026-06-21T12:00:02Z","deleted_at":"2026-06-21T12:05:00Z"},
            {"id":"c-3","task_id":"t-1","body":"third","created_by":"u-1","created_at":"2026-06-21T12:00:03Z"}
        ]}}
    """.trimIndent()

    @Test
    fun fetchHitsTheThreadPathAndMapsLiveComments() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorCommentRemoteSource(client { req -> captured = req; respondJson(threadEnvelope) })

        val snapshot = source.fetchComments(TaskId("t-1"))

        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/t-1/comments") == true)
        val comments = (snapshot as RemoteSnapshot.Available).value
        // c-2 is server-tombstoned — dropped at the source so the reconcile sees "gone".
        assertEquals(listOf("c-1", "c-3"), comments.map { it.id })
        assertEquals("first", comments.first().body)
    }

    @Test
    fun fetchIsUnavailableOnFailure() = runTest {
        val source = KtorCommentRemoteSource(client { respond("", HttpStatusCode.InternalServerError) })

        assertEquals(RemoteSnapshot.Unavailable, source.fetchComments(TaskId("t-1")))
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
