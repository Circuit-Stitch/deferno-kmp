package com.circuitstitch.deferno.core.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives [KoogInferenceEngine] end to end through Koog's real client against a Ktor [MockEngine]
 * answering canned Anthropic-format responses (no network — ADR-0006 JVM-fast path): the
 * structured-output success path, the malformed-output → repair → typed-error paths (#147
 * acceptance), transport failures, and the no-credential floor.
 */
class KoogInferenceEngineTest {

    /** What the wire saw: every request's URL, headers, and body, in order. */
    private class RecordedRequest(val url: String, val apiKey: String?, val body: String)

    private val expected = DraftTasks(
        drafts = listOf(
            Draft(title = "Mow the lawn", productive = true),
            Draft(title = "Sharpen the mower blades", completeBy = "2026-06-12"),
        ),
    )

    private val validOutput = Json.encodeToString(DraftTasks.serializer(), expected)

    private fun request() = InferenceRequest(
        instructions = "Extract draft tasks from the transcript.",
        content = "mow the lawn, then sharpen the mower blades by Friday",
        schema = InferenceSchema(DraftTasks.serializer()),
    )

    /** A minimal Anthropic Messages-API success body whose assistant text is [text]. */
    private fun anthropicMessage(text: String): String = buildJsonObject {
        put("id", "msg_test")
        put("type", "message")
        put("role", "assistant")
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
        put("model", "claude-haiku-4-5")
        put("stop_reason", "end_turn")
    }.toString()

    /** An engine whose wire is [responses], served in order; [recorded] collects what was sent. */
    private fun engine(
        responses: List<Pair<HttpStatusCode, String>>,
        recorded: MutableList<RecordedRequest>,
        credentials: InferenceCredentialProvider = InferenceCredentialProvider { "relay-pat" },
        repairRetries: Int = 0,
    ): KoogInferenceEngine {
        val mock = HttpClient(
            MockEngine { request ->
                val call = recorded.size
                if (call >= responses.size) fail("unexpected request #${call + 1} to ${request.url}")
                recorded += RecordedRequest(
                    url = request.url.toString(),
                    apiKey = request.headers["x-api-key"],
                    body = request.body.toByteArray().decodeToString(),
                )
                val (status, body) = responses[call]
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        return KoogInferenceEngine(
            endpoint = AnthropicEndpoint(baseUrl = "https://relay.test", credentials = credentials),
            repairRetries = repairRetries,
            baseHttpClient = mock,
        )
    }

    @Test
    fun roundTripsStructuredOutputThroughTheConfiguredEndpoint() = runTest {
        val recorded = mutableListOf<RecordedRequest>()
        val engine = engine(listOf(HttpStatusCode.OK to anthropicMessage(validOutput)), recorded)

        val result = engine.infer(request())

        assertEquals(expected, assertIs<InferenceResult.Success<DraftTasks>>(result).value)
        // The configurable-endpoint acceptance (#147): the call hit the configured base URL with the
        // provided credential, and the request carried the schema id — the same wire shape the
        // Deferno relay will pass through verbatim (ADR-0027).
        val sent = recorded.single()
        assertEquals("https://relay.test/v1/messages", sent.url)
        assertEquals("relay-pat", sent.apiKey)
        assertTrue("draft_tasks" in sent.body, "request should carry the schema id")
        assertTrue("Extract draft tasks" in sent.body, "request should carry the instructions")
    }

    @Test
    fun surfacesUnrepairedMalformedOutputAsATypedError() = runTest {
        val recorded = mutableListOf<RecordedRequest>()
        val engine = engine(
            listOf(HttpStatusCode.OK to anthropicMessage("definitely not the schema")),
            recorded,
            repairRetries = 0,
        )

        val result = engine.infer(request())

        assertIs<InferenceResult.Failure.MalformedOutput>(result)
        assertEquals(1, recorded.size)
    }

    @Test
    fun repairsMalformedOutputWithAFollowUpCall() = runTest {
        val recorded = mutableListOf<RecordedRequest>()
        val engine = engine(
            listOf(
                HttpStatusCode.OK to anthropicMessage("definitely not the schema"),
                HttpStatusCode.OK to anthropicMessage(validOutput),
            ),
            recorded,
            repairRetries = 1,
        )

        val result = engine.infer(request())

        assertEquals(expected, assertIs<InferenceResult.Success<DraftTasks>>(result).value)
        assertEquals(2, recorded.size, "repair should have made exactly one follow-up call")
    }

    @Test
    fun surfacesMalformedOutputThatDefeatsRepairAsATypedError() = runTest {
        val recorded = mutableListOf<RecordedRequest>()
        val engine = engine(
            listOf(
                HttpStatusCode.OK to anthropicMessage("definitely not the schema"),
                HttpStatusCode.OK to anthropicMessage("still not the schema"),
            ),
            recorded,
            repairRetries = 1,
        )

        val result = engine.infer(request())

        assertIs<InferenceResult.Failure.MalformedOutput>(result)
        assertEquals(2, recorded.size)
    }

    @Test
    fun mapsWireFailuresToTransport() = runTest {
        val recorded = mutableListOf<RecordedRequest>()
        val engine = engine(
            listOf(HttpStatusCode.InternalServerError to """{"error":"overloaded"}"""),
            recorded,
        )

        val result = engine.infer(request())

        assertIs<InferenceResult.Failure.Transport>(result)
    }

    @Test
    fun answersNotConfiguredWithoutTouchingTheWire() = runTest {
        val recorded = mutableListOf<RecordedRequest>()
        val engine = engine(
            responses = emptyList(), // any request fails the test
            recorded = recorded,
            credentials = InferenceCredentialProvider.Unconfigured,
        )

        val result = engine.infer(request())

        assertIs<InferenceResult.Failure.NotConfigured>(result)
        assertTrue(recorded.isEmpty(), "an unconfigured engine must make no network call")
    }
}
