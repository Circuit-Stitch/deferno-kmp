package com.circuitstitch.deferno.core.data.outbox

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The HTTP mapping of [KtorOutboxRequestSender] (#23), driven by Ktor's MockEngine on the JVM-fast
 * path (ADR-0006) — no real network. Proves it issues the right verb + path, sends the stored body
 * **verbatim** as `application/json` (the byte-identical replay idempotency relies on this), and
 * classifies HTTP statuses into the queue's three-way [SendOutcome] (including a transport failure).
 */
class KtorOutboxRequestSenderTest {

    @Test
    fun patchSendsTheVerbatimJsonBody() = runTest {
        var captured: HttpRequestData? = null
        val sender = KtorOutboxRequestSender(client { req -> captured = req; respond("", HttpStatusCode.OK) })

        val outcome = sender.send(OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"status":"done"}"""))

        assertEquals(SendOutcome.Success, outcome)
        val request = captured!!
        assertEquals(HttpMethod.Patch, request.method)
        assertTrue(request.url.encodedPath.endsWith("/tasks/a"))
        val body = request.body as TextContent
        assertEquals("""{"status":"done"}""", body.text) // exact bytes, not re-encoded
        assertEquals("application/json", body.contentType.toString())
    }

    @Test
    fun deleteSendsNoBodyAndAcceptsNoContent() = runTest {
        var captured: HttpRequestData? = null
        val sender = KtorOutboxRequestSender(client { req -> captured = req; respond("", HttpStatusCode.NoContent) })

        val outcome = sender.send(OutboxRequest(OutboxMethod.Delete, listOf("tasks", "a")))

        assertEquals(SendOutcome.Success, outcome) // 204
        val request = captured!!
        assertEquals(HttpMethod.Delete, request.method)
        assertTrue(request.body !is TextContent) // no JSON body attached
    }

    @Test
    fun classifiesHttpStatusesIntoOutcomes() = runTest {
        suspend fun outcomeFromStatus(code: Int): SendOutcome {
            val sender = KtorOutboxRequestSender(client { respond("", HttpStatusCode.fromValue(code)) })
            return sender.send(OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), "{}"))
        }
        assertEquals(SendOutcome.Success, outcomeFromStatus(200))
        assertEquals(SendOutcome.Success, outcomeFromStatus(404))
        assertEquals(SendOutcome.Retryable, outcomeFromStatus(401))
        assertEquals(SendOutcome.Retryable, outcomeFromStatus(503))
        assertEquals(SendOutcome.Terminal, outcomeFromStatus(409))
    }

    @Test
    fun aTransportFailureIsRetryable() = runTest {
        val sender = KtorOutboxRequestSender(client { throw RuntimeException("connection refused") })

        val outcome = sender.send(OutboxRequest(OutboxMethod.Post, listOf("tasks", "plan", "add"), "{}"))

        assertEquals(SendOutcome.Retryable, outcome)
    }

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        defaultRequest { url("https://api.example.test/") }
    }
}
