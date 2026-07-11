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

    @Test
    fun sendCreateParsesTheServerAssignedIdFromTheRealCreateEnvelope() = runTest {
        // The exact staging `POST /tasks/{id}/comments` 201 body, captured live off a device. Regression
        // for the offline comment-create id-heal: `core:data` has no serialization *compiler* plugin, so
        // an `@Serializable` envelope DTO here decodes to nothing (throws → blank id, swallowed), and the
        // optimistic comment is never re-keyed to the server id → a duplicate row + a silently-lost edit.
        // `parseCreatedId` must read the id off the JsonElement tree (runtime-only) instead.
        val body = """{"version":"0.1","data":{"id":"f752db31-7653-4cce-96c1-71d89ccaa705","task_id":"t",""" +
            """"created_by":"u","created_at":"2026-07-10T23:52:55.644147335Z","edited_at":null,""" +
            """"deleted_at":null,"is_private":false,"body_enc":{"nonce":"n","ct":"c"},"body":"hi"}}"""
        val sender = KtorOutboxRequestSender(client { respond(body, HttpStatusCode.Created) })

        val outcome = sender.sendCreate(OutboxRequest(OutboxMethod.Post, listOf("tasks", "t", "comments"), """{"body":"hi"}"""))

        assertEquals(CreateSendOutcome.Created("f752db31-7653-4cce-96c1-71d89ccaa705"), outcome)
    }

    @Test
    fun sendCreateReturnsABlankIdWhenTheSuccessBodyCarriesNoId() = runTest {
        // A 2xx whose body has no parseable `data.id` still succeeds with a blank id (the create landed
        // server-side; a missing id just means no heal) — never a throw, never a retry.
        val sender = KtorOutboxRequestSender(client { respond("""{"version":"0.1"}""", HttpStatusCode.Created) })

        val outcome = sender.sendCreate(OutboxRequest(OutboxMethod.Post, listOf("tasks", "t", "comments"), """{"body":"hi"}"""))

        assertEquals(CreateSendOutcome.Created(""), outcome)
    }

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        defaultRequest { url("https://api.example.test/") }
    }
}
