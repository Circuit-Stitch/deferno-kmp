package com.circuitstitch.deferno.feature.assistant

import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.model.OrgId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Assistant SSE wire format (#282, ADR-0040) — the contained, provisional frame↔event mapping
 * (Deferno#485). Previously stranded in `iosMain` (untested); now common + covered on the JVM fast-path
 * (ADR-0006). Reconciled against the live staging stream: `text` carries a raw reply chunk, `done` the
 * `[DONE]` sentinel, the structured frames a JSON object.
 */
class AssistantWireFormatTest {

    private val request = AssistantTurnRequest(OrgId("org-1"), ConversationId("c-1"), "hi there")

    // --- request shape ---

    @Test
    fun turnUrlScopesTheOrgAndTrimsATrailingSlash() {
        assertEquals(
            "https://api.test/orgs/org-1/assistant/messages",
            AssistantWireFormat.turnUrl("https://api.test/", request),
        )
        assertEquals(
            "https://api.test/orgs/org-1/assistant/messages",
            AssistantWireFormat.turnUrl("https://api.test", request),
        )
    }

    @Test
    fun turnBodyMintsTheConversationIdInSnakeCase() {
        val body = AssistantWireFormat.turnBody(request)
        assertTrue(body.contains("\"conversation_id\":\"c-1\""), body)
        assertTrue(body.contains("\"message\":\"hi there\""), body)
    }

    // --- text + done (the live-verified frames) ---

    @Test
    fun textFrameIsTheRawReplyDeltaNotJson() {
        val event = assertIs<AssistantEvent.TextDelta>(AssistantWireFormat.toEvent("text", "Hello, world"))
        assertEquals("Hello, world", event.text)
    }

    @Test
    fun anEmptyTextFrameIsIgnored() {
        assertNull(AssistantWireFormat.toEvent("text", ""))
    }

    @Test
    fun doneFrameIsTheTerminalRegardlessOfPayload() {
        assertEquals(AssistantEvent.Done, AssistantWireFormat.toEvent("done", "[DONE]"))
    }

    @Test
    fun eventNameIsNormalized_crlfTrimmedUppercaseUnderscored() {
        // The reader hands the name as-is; a stray case / CRLF whitespace / underscore must still map.
        assertEquals(AssistantEvent.Done, AssistantWireFormat.toEvent(" DONE\r", "x"))
        val delta = assertIs<AssistantEvent.TextDelta>(AssistantWireFormat.toEvent("TEXT_DELTA", "y"))
        assertEquals("y", delta.text)
    }

    // --- structured frames ---

    @Test
    fun conversationFrameIsConfirmatoryAndIgnored() {
        assertNull(AssistantWireFormat.toEvent("conversation", """{"id":"c-1"}"""))
    }

    @Test
    fun proposalFrameCarriesTheOpaqueInputVerbatim() {
        val event = assertIs<AssistantEvent.Proposal>(
            AssistantWireFormat.toEvent(
                "proposal",
                """{"tool":"delete_items","input":{"ids":["a","b"]},"summary":"Delete 2 tasks"}""",
            ),
        )
        assertEquals("delete_items", event.proposal.tool)
        assertEquals("Delete 2 tasks", event.proposal.summary)
        // The input round-trips as the same JSON structure (the client never interprets it).
        assertTrue(event.proposal.input.contains("\"ids\""), event.proposal.input)
    }

    @Test
    fun usageFrameSurfacesExhaustion() {
        val event = assertIs<AssistantEvent.Usage>(
            AssistantWireFormat.toEvent("usage", """{"remaining":0,"exhausted":true}"""),
        )
        assertEquals(0, event.remaining)
        assertTrue(event.exhausted)
    }

    @Test
    fun toolCallFrameMapsToolAndInput() {
        val event = assertIs<AssistantEvent.ToolCall>(
            AssistantWireFormat.toEvent("tool_call", """{"tool":"search","input":"tasks"}"""),
        )
        assertEquals("search", event.tool)
        assertEquals("tasks", event.input)
    }

    @Test
    fun errorFrameReadsJsonMessageOrFallsBackToRawText() {
        val fromJson = assertIs<AssistantEvent.Error>(
            AssistantWireFormat.toEvent("error", """{"message":"rate limited"}"""),
        )
        assertEquals("rate limited", fromJson.message)

        // A non-JSON error payload is used verbatim rather than swallowed.
        val fromRaw = assertIs<AssistantEvent.Error>(AssistantWireFormat.toEvent("error", "boom"))
        assertEquals("boom", fromRaw.message)

        // An empty error payload still produces a human message, never a blank one.
        val fromBlank = assertIs<AssistantEvent.Error>(AssistantWireFormat.toEvent("error", ""))
        assertTrue(fromBlank.message.isNotBlank())
    }

    @Test
    fun anUnknownFrameIsIgnored() {
        assertNull(AssistantWireFormat.toEvent("heartbeat", "ping"))
        assertNull(AssistantWireFormat.toEvent("", ""))
    }
}
