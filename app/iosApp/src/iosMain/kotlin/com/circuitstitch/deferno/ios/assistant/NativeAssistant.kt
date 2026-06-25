package com.circuitstitch.deferno.ios.assistant

import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.feature.assistant.AssistantEvent
import com.circuitstitch.deferno.feature.assistant.AssistantStream
import com.circuitstitch.deferno.feature.assistant.AssistantTurnRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The iOS SSE turn-stream transport port the Swift app implements (#282, ADR-0040) — the iOS twin of the
 * native inference/transcriber seams (ADR-0037). Kotlin owns the **request** (URL, Bearer PAT, JSON body)
 * and the **parsing**; Swift only opens a raw `URLSession` byte stream (NSURLSession SSE doesn't buffer like
 * Ktor's Darwin engine, ADR-0040), reads SSE frames, and hands each frame's `(eventType, data)` back as
 * plain Strings, then signals completion or failure exactly once. So a backend wire-format change is
 * contained to [turnUrl], [turnBody], and [toAssistantEvent] here (the ADR's "isolate the wire" guarantee).
 */
interface NativeAssistantTransport {
    /**
     * Open one SSE turn. [url] is the fully-formed endpoint, [authToken] the Bearer PAT (may be null when
     * signed out), [body] the JSON request. Swift calls [onEvent] per SSE frame, then **exactly one** of
     * [onDone] (stream closed cleanly) / [onError] (transport failure). Returns a [NativeStreamHandle]
     * whose [NativeStreamHandle.cancel] aborts the underlying task (wired to the Flow's `awaitClose`).
     */
    fun stream(
        url: String,
        authToken: String?,
        body: String,
        onEvent: (eventType: String, data: String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ): NativeStreamHandle
}

/** A handle to an in-flight [NativeAssistantTransport.stream] — [cancel] aborts the URLSession task. */
interface NativeStreamHandle {
    fun cancel()
}

/**
 * Adapts a Swift [NativeAssistantTransport] to the shared [AssistantStream] seam the chat component drives.
 * One turn is a `callbackFlow` (multi-emit — NOT `suspendCancellableCoroutine`): each parsed [AssistantEvent]
 * is sent as it streams; a terminal frame, a clean close, or a transport error each completes the flow
 * **once** (the `complete` guard); `awaitClose` cancels the URLSession task when the collector goes away
 * (e.g. `onCancelTurn`). [token] is read fresh per turn (Active-Account PAT), never cached.
 */
class NativeAssistantStream(
    private val transport: NativeAssistantTransport,
    private val baseUrl: String,
    private val token: () -> String?,
) : AssistantStream {

    override fun streamTurn(request: AssistantTurnRequest): Flow<AssistantEvent> = callbackFlow {
        // The transport calls back serially from its single reader task, so a plain flag guards the
        // one-completion invariant (no concurrent terminal). close() is idempotent regardless. A lambda
        // (not a local `fun`) so it captures this callbackFlow's ProducerScope receiver (trySend/close).
        var completed = false
        val complete: (AssistantEvent?) -> Unit = { terminal ->
            if (!completed) {
                completed = true
                terminal?.let { trySend(it) }
                close()
            }
        }

        val handle = transport.stream(
            url = turnUrl(baseUrl, request),
            authToken = token(),
            body = turnBody(request),
            onEvent = { type, data ->
                val event = toAssistantEvent(type, data)
                when {
                    event == null -> Unit // unknown/heartbeat frame — ignore
                    event == AssistantEvent.Done -> complete(AssistantEvent.Done)
                    event is AssistantEvent.Error -> complete(event)
                    else -> trySend(event)
                }
            },
            onDone = { complete(AssistantEvent.Done) },
            onError = { message -> complete(AssistantEvent.Error(message)) },
        )

        awaitClose { handle.cancel() }
    }
}

// --- WIRE-DEPENDENT (Deferno#485): isolated top-level so a backend change is a contained edit (ADR-0040). ---

/** The turn endpoint (verified against staging: `POST /orgs/{org}/assistant/messages` streams the reply). */
private fun turnUrl(baseUrl: String, request: AssistantTurnRequest): String =
    "${baseUrl.trimEnd('/')}/orgs/${request.orgId.value}/assistant/messages"

/** The turn request body — snake_case per the DTO convention; the client mints `conversation_id` (#185). */
private fun turnBody(request: AssistantTurnRequest): String =
    buildJsonObject {
        put("conversation_id", request.conversationId.value)
        put("message", request.message)
    }.toString()

/**
 * Map one SSE frame to a typed [AssistantEvent], or `null` to ignore (heartbeat / unknown). Reconciled
 * against the live staging stream (2026-06-25): the frame format is **mixed** — `text`'s `data:` is the raw
 * reply chunk (NOT JSON), `done`'s is the `[DONE]` sentinel, and the structured frames (`conversation`,
 * `proposal`, `usage`, `error`) carry a JSON object. `tool-call`/`tool-result`/`proposal`/`usage` weren't
 * exercised by the simple turn, so they stay tolerant/provisional until a richer turn confirms them.
 */
private fun toAssistantEvent(eventType: String, data: String): AssistantEvent? {
    val type = eventType.trim().lowercase().replace('_', '-')

    // `text`: the data is the raw reply delta (accrued by the component), not a JSON envelope.
    if (type in TEXT_EVENTS) return data.takeIf { it.isNotEmpty() }?.let { AssistantEvent.TextDelta(it) }
    // `done`: the data is the `[DONE]` sentinel — the event alone is the signal.
    if (type in DONE_EVENTS) return AssistantEvent.Done

    val json = runCatching { DefernoJson.parseToJsonElement(data) }.getOrNull() as? JsonObject
    return when (type) {
        // The server echoes the conversation id; the client already keys on the id it sent, so this is
        // confirmatory and ignored (validated: the sent id is honored — see the turn body's conversation_id).
        "conversation" -> null
        "tool-call", "tool-use" ->
            AssistantEvent.ToolCall(json.str("tool") ?: json.str("name") ?: "", json.str("input") ?: "")
        "tool-result" ->
            AssistantEvent.ToolResult(
                json.str("tool") ?: json.str("name") ?: "",
                json.str("output") ?: json.str("result") ?: "",
            )
        "proposal" -> json?.let {
            AssistantEvent.Proposal(
                AssistantProposal(
                    tool = it.str("tool") ?: it.str("name") ?: "",
                    input = it["input"]?.toString() ?: "",
                    summary = it.str("summary") ?: it.str("description") ?: "",
                ),
            )
        }
        "usage" -> AssistantEvent.Usage(
            remaining = (json?.get("remaining") as? JsonPrimitive)?.intOrNull,
            exhausted = (json?.get("exhausted") as? JsonPrimitive)?.booleanOrNull ?: false,
        )
        // `error` may arrive as a JSON object or a raw string — handle both.
        "error" -> AssistantEvent.Error(json.str("message") ?: json.str("error") ?: data.ifBlank { "The turn failed. Try again." })
        else -> null
    }
}

/** `event:` names whose `data:` is the raw reply text (a streamed delta), not a JSON envelope. */
private val TEXT_EVENTS = setOf("text", "text-delta", "delta", "content", "content-delta", "message-delta")

/** `event:` names that signal the turn finished cleanly (the `data:` payload — e.g. `[DONE]` — is ignored). */
private val DONE_EVENTS = setOf("done", "end", "complete", "stop")

private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
