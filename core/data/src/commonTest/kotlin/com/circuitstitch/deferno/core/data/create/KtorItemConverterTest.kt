package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
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
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Behaviour of [KtorItemConverter] (ADR-0016, #364) over Ktor's MockEngine (ADR-0006 JVM-fast path):
 * convert POSTs the contract's body to `/items/{id}/convert` and condenses the polymorphic response to the
 * kind the item became, and a 4xx maps to [ApiResult.Failure] so the writer can surface the gentle error.
 */
class KtorItemConverterTest {

    @Test
    fun convertPostsToItemsConvertAndMapsTheNewKind() = runTest {
        var captured: HttpRequestData? = null
        var sentBody: String? = null
        val source = KtorItemConverter(
            client { req ->
                captured = req
                sentBody = (req.body as? TextContent)?.text
                respondJson("""{"version":"0.1","data":{"type":"chore","id":"item-1","org_slug":"u-e4h2qk","title":"trash","status":"active","date_created":"2026-05-12T19:52:01Z","cadence_mode":"rolling","recurrence":{"type":"weekly","days":["Tue"]}}}""")
            },
        )

        val result = source.convert(
            "item-1",
            ConvertItemPayload(to = "chore", recurrence = RecurrenceDto("weekly")),
            ActivityStamp("entry-3", Instant.parse("2026-04-17T10:00:00Z")),
        )

        assertEquals(HttpMethod.Post, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/items/item-1/convert") == true)
        // The destination kind's key is `to` — the server's ConvertItemPayload requires it with no alias
        // and no default, so a body naming it `type` is a 422 on every convert. The client sent exactly
        // that for months because the test pinned the client's own DTO instead of the contract. A
        // `contains("type")` check cannot catch it either: `recurrence.type` is a legitimate nested key,
        // which is why the absence assertion is scoped to the top-level key set.
        val raw = assertNotNull(sentBody, "the convert body was sent")
        val body = DefernoJson.parseToJsonElement(raw).jsonObject
        assertEquals("chore", body.getValue("to").jsonPrimitive.content)
        assertFalse("type" in body, "the destination kind is never sent under the client-only `type` key")
        assertEquals("weekly", body.getValue("recurrence").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("entry-3", body.getValue("activity").jsonObject.getValue("id").jsonPrimitive.content)
        // The whole body, pinned: a new defaulted field on ConvertItemPayload breaks this deliberately —
        // whether it belongs on the wire is a decision to make, not a detail to absorb.
        assertEquals(
            """{"to":"chore","recurrence":{"type":"weekly"},""" +
                """"activity":{"id":"entry-3","at":"2026-04-17T10:00:00Z","source":"mobile"}}""",
            raw,
        )
        val converted = assertIs<ApiResult.Success<ConvertedItem>>(result).data
        assertEquals(ItemKind.Chore, converted.kind)
    }

    @Test
    fun aServerRejectionMapsToFailure() = runTest {
        val source = KtorItemConverter(client { respond("", HttpStatusCode.UnprocessableEntity) })

        val result = source.convert(
            "item-1",
            ConvertItemPayload(to = "chore"),
            ActivityStamp("entry-3", Instant.parse("2026-04-17T10:00:00Z")),
        )

        assertIs<ApiResult.Failure>(result)
    }

    // --- helpers ---

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        // The production DefernoJson, not a stand-in: a default Json emits explicit nulls, so a body pinned
        // against a stand-in would pass here and diverge from the bytes the app actually POSTs.
        install(ContentNegotiation) { json(DefernoJson) }
        defaultRequest { url("https://api.example.test/") }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}
