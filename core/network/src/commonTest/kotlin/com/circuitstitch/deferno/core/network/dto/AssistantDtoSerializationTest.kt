package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire contract for the [[Assistant]] DTOs (#282, ADR-0040) decoded/encoded by the tolerant reader
 * ([DefernoJson]): faithful snake_case reads, `null`-vs-omit on writes, the opaque-JSON proposal input
 * round-tripping verbatim, and the new get-messages payload (Deferno#485) parsing.
 */
class AssistantDtoSerializationTest {

    @Test
    fun availabilityParsesAllThreeFlagsAndOptionalDisclosure() {
        val dto = DefernoJson.decodeFromString<AssistantAvailabilityDto>(
            """{"available":true,"entitled":true,"enabled":true,"disclosure":"we send items to a third party"}""",
        )
        assertTrue(dto.available && dto.entitled && dto.enabled)
        assertEquals("we send items to a third party", dto.disclosure)
    }

    @Test
    fun availabilityToleratesMissingFieldsViaDefaults() {
        // The tolerant reader degrades a sparse body to defaults rather than throwing (additive wire).
        val dto = DefernoJson.decodeFromString<AssistantAvailabilityDto>("""{"entitled":true}""")
        assertTrue(dto.entitled)
        assertFalse(dto.available)
        assertNull(dto.disclosure)
    }

    @Test
    fun enablementRequestSerializesTheEnabledFlag() {
        assertContains(DefernoJson.encodeToString(EnablementRequestDto(enabled = true)), "\"enabled\":true")
    }

    @Test
    fun enablementResponseCarriesTheDisclosure() {
        val dto = DefernoJson.decodeFromString<EnablementResponseDto>(
            """{"enabled":true,"disclosure":"consent text"}""",
        )
        assertTrue(dto.enabled)
        assertEquals("consent text", dto.disclosure)
    }

    @Test
    fun proposalInputRoundTripsAsOpaqueJsonVerbatim() {
        val input = DefernoJson.parseToJsonElement("""{"ids":["a","b"],"count":8}""")
        val json = DefernoJson.encodeToString(
            AssistantProposalDto(tool = "delete_items", input = input, summary = "Delete 8 tasks"),
        )
        assertContains(json, "\"tool\":\"delete_items\"")
        assertContains(json, "\"summary\":\"Delete 8 tasks\"")
        // The opaque input is embedded as a JSON value, not a stringified blob.
        assertContains(json, "\"ids\":[\"a\",\"b\"]")
        assertContains(json, "\"count\":8")

        // ...and it decodes back to the same JSON value.
        val back = DefernoJson.decodeFromString<AssistantProposalDto>(json)
        val obj = back.input as JsonObject
        assertEquals(JsonPrimitive(8), obj["count"])
    }

    @Test
    fun applyResultParsesWithOptionalResult() {
        assertTrue(DefernoJson.decodeFromString<ApplyResultDto>("""{"applied":true}""").applied)
        assertNull(DefernoJson.decodeFromString<ApplyResultDto>("""{"applied":false}""").result)
    }

    @Test
    fun conversationsResponseParsesIdList() {
        val dto = DefernoJson.decodeFromString<ConversationsResponseDto>(
            """{"conversations":["c-1","c-2","c-3"]}""",
        )
        assertEquals(listOf("c-1", "c-2", "c-3"), dto.conversations)
    }

    @Test
    fun conversationDetailParsesMessageLog() {
        val dto = DefernoJson.decodeFromString<ConversationDetailDto>(
            """{"id":"c-1","title":"Cleanup","updated_at":"2026-06-24T10:00:00Z","messages":[
                {"id":"m-1","role":"user","text":"clean up","created_at":"2026-06-24T10:00:00Z"},
                {"id":"m-2","role":"assistant","text":"done","created_at":"2026-06-24T10:00:05Z"}
            ]}""",
        )
        assertEquals("c-1", dto.id)
        assertEquals("Cleanup", dto.title)
        assertEquals(2, dto.messages.size)
        assertEquals("assistant", dto.messages[1].role)
        assertEquals("done", dto.messages[1].text)
    }
}
