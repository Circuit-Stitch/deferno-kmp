package com.circuitstitch.deferno.feature.assistant

import com.circuitstitch.deferno.core.model.AssistantProposal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The Assistant SSE turn-stream **wire format** (#282, ADR-0040, Deferno#485) — the one contained edit point
 * when the backend wire changes. Pure, platform-free Kotlin: the iOS transport ([NativeAssistantStream], in
 * `iosMain`) only opens the byte stream and hands raw `(eventType, data)` frames here; this owns the
 * URL/body shape and the frame→[AssistantEvent] mapping. It lives in `commonMain` precisely so it is
 * unit-tested on the JVM fast-path (the trickiest, most provisional code in the slice — the CRLF frame-merge
 * bug lived next to it) and is reusable by the Android/desktop transports that follow (the seam keeps
 * `ktor-client-sse` an option).
 */
object AssistantWireFormat {

    // A tolerant reader (additive wire). No @Serializable types here — just JsonElement walking — so the
    // module needs the kotlinx-serialization-json runtime but not its compiler plugin.
    private val json = Json { ignoreUnknownKeys = true }

    /** The turn endpoint (verified against staging: `POST /orgs/{org}/assistant/messages` streams the reply). */
    fun turnUrl(baseUrl: String, request: AssistantTurnRequest): String =
        "${baseUrl.trimEnd('/')}/orgs/${request.orgId.value}/assistant/messages"

    /** The turn request body — snake_case per the DTO convention; the client mints `conversation_id` (#185). */
    fun turnBody(request: AssistantTurnRequest): String =
        buildJsonObject {
            put("conversation_id", request.conversationId.value)
            put("message", request.message)
        }.toString()

    /**
     * Map one SSE frame to a typed [AssistantEvent], or `null` to ignore (heartbeat / unknown). Reconciled
     * against the live staging stream (2026-06-25): the frame format is **mixed** — `text`'s `data:` is the
     * raw reply chunk (NOT JSON), `done`'s is the `[DONE]` sentinel, and the structured frames
     * (`conversation`, `proposal`, `usage`, `error`) carry a JSON object. `tool-call` / `tool-result` /
     * `proposal` / `usage` weren't exercised by a simple turn, so they stay tolerant/provisional until a
     * richer turn confirms them (Deferno#485) — at which point this is the single place to reconcile.
     */
    fun toEvent(eventType: String, data: String): AssistantEvent? {
        val type = eventType.trim().lowercase().replace('_', '-')

        // `text`: the data is the raw reply delta (accrued by the component), not a JSON envelope.
        if (type in TEXT_EVENTS) return data.takeIf { it.isNotEmpty() }?.let { AssistantEvent.TextDelta(it) }
        // `done`: the data is the `[DONE]` sentinel — the event alone is the signal.
        if (type == "done") return AssistantEvent.Done

        val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject
        return when (type) {
            // The server echoes the conversation id; the client already keys on the id it sent, so this is
            // confirmatory and ignored (validated: the sent id is honored — see [turnBody]'s conversation_id).
            "conversation" -> null
            "tool-call", "tool-use" ->
                AssistantEvent.ToolCall(obj.str("tool") ?: obj.str("name") ?: "", obj.str("input") ?: "")
            "tool-result" ->
                AssistantEvent.ToolResult(
                    obj.str("tool") ?: obj.str("name") ?: "",
                    obj.str("output") ?: obj.str("result") ?: "",
                )
            "proposal" -> obj?.let {
                AssistantEvent.Proposal(
                    AssistantProposal(
                        tool = it.str("tool") ?: it.str("name") ?: "",
                        input = it["input"]?.toString() ?: "",
                        summary = it.str("summary") ?: it.str("description") ?: "",
                    ),
                )
            }
            "usage" -> AssistantEvent.Usage(
                remaining = (obj?.get("remaining") as? JsonPrimitive)?.intOrNull,
                exhausted = (obj?.get("exhausted") as? JsonPrimitive)?.booleanOrNull ?: false,
            )
            // `error` may arrive as a JSON object or a raw string — handle both.
            "error" -> AssistantEvent.Error(
                obj.str("message") ?: obj.str("error") ?: data.ifBlank { "The turn failed. Try again." },
            )
            else -> null
        }
    }

    // `event:` names whose `data:` is the raw reply text (a streamed delta), not a JSON envelope. Trimmed to
    // what staging emits (`text`) plus the taxonomy's canonical name (`text-delta`); grow it on reconcile.
    private val TEXT_EVENTS = setOf("text", "text-delta")

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
}
