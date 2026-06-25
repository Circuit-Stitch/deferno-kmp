package com.circuitstitch.deferno.core.data.assistant

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.model.OrgId
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
 * Behaviour of [KtorAssistantClient] (#282, ADR-0040) over Ktor's MockEngine (ADR-0006, no real network).
 * Proves each endpoint hits the right `/orgs/{org_id}/assistant…` path + method, condenses the wire DTO to
 * the domain gate/types, and that a not-available/forbidden response maps to [ApiResult.Failure].
 */
class KtorAssistantClientTest {

    private val org = OrgId("org-1")

    @Test
    fun availabilityHitsAssistantPathAndMapsTheGate() = runTest {
        var captured: HttpRequestData? = null
        val client = KtorAssistantClient(
            client { req -> captured = req; respondJson("""{"version":"0.1","data":{"available":true,"entitled":true,"enabled":true,"disclosure":"d"}}""") },
        )

        val result = client.availability(org)

        assertEquals(HttpMethod.Get, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/orgs/org-1/assistant") == true)
        val gate = assertIs<RemoteSnapshot.Available<*>>(result).value as com.circuitstitch.deferno.core.model.AssistantAvailability
        assertTrue(gate.available)
        assertEquals("d", gate.disclosure)
    }

    @Test
    fun setEnablementPutsToEnablementAndImpliesEntitled() = runTest {
        var captured: HttpRequestData? = null
        val client = KtorAssistantClient(
            client { req -> captured = req; respondJson("""{"version":"0.1","data":{"enabled":true,"disclosure":"consent"}}""") },
        )

        val result = client.setEnablement(org, enabled = true)

        assertEquals(HttpMethod.Put, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/orgs/org-1/assistant/enablement") == true)
        val gate = assertIs<RemoteSnapshot.Available<*>>(result).value as com.circuitstitch.deferno.core.model.AssistantAvailability
        assertTrue(gate.entitled && gate.enabled && gate.available)
        assertEquals("consent", gate.disclosure)
    }

    @Test
    fun applyPostsTheProposalAndReturnsApplied() = runTest {
        var captured: HttpRequestData? = null
        val client = KtorAssistantClient(
            client { req -> captured = req; respondJson("""{"version":"0.1","data":{"applied":true}}""") },
        )

        val result = client.apply(org, AssistantProposal(tool = "delete_items", input = """{"ids":["a"]}""", summary = "Delete 1"))

        assertEquals(HttpMethod.Post, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/orgs/org-1/assistant/apply") == true)
        assertEquals(true, assertIs<RemoteSnapshot.Available<Boolean>>(result).value)
    }

    @Test
    fun conversationsParsesTheIdList() = runTest {
        val client = KtorAssistantClient(
            client { respondJson("""{"version":"0.1","data":{"conversations":["c-1","c-2"]}}""") },
        )

        val result = client.conversations(org)

        assertEquals(listOf(ConversationId("c-1"), ConversationId("c-2")), assertIs<RemoteSnapshot.Available<List<ConversationId>>>(result).value)
    }

    @Test
    fun conversationHitsTheIdPathAndMapsTheMessageLog() = runTest {
        var captured: HttpRequestData? = null
        val client = KtorAssistantClient(
            client { req ->
                captured = req
                respondJson("""{"version":"0.1","data":{"id":"c-1","title":"Cleanup","updated_at":"2026-06-24T10:00:00Z","messages":[{"id":"m1","role":"user","text":"hi","created_at":"2026-06-24T10:00:00Z"},{"id":"m2","role":"assistant","text":"done","created_at":"2026-06-24T10:00:05Z"}]}}""")
            },
        )

        val result = client.conversation(org, ConversationId("c-1"))

        assertTrue(captured?.url?.encodedPath?.endsWith("/orgs/org-1/assistant/conversations/c-1") == true)
        val detail = assertIs<RemoteSnapshot.Available<*>>(result).value as com.circuitstitch.deferno.core.model.ConversationDetail
        assertEquals("Cleanup", detail.conversation.title)
        assertEquals(listOf("hi", "done"), detail.messages.map { it.text })
    }

    @Test
    fun aForbiddenResponseMapsToFailure() = runTest {
        val client = KtorAssistantClient(client { respond("", HttpStatusCode.Forbidden) })

        assertIs<RemoteSnapshot.Unavailable>(client.availability(org))
    }

    // --- helpers (mirror the other Ktor remote-source tests) ---

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
