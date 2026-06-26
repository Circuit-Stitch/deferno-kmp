package com.circuitstitch.deferno.macos.assistant

import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.feature.assistant.AssistantEvent
import com.circuitstitch.deferno.feature.assistant.AssistantStream
import com.circuitstitch.deferno.feature.assistant.AssistantTurnRequest
import com.circuitstitch.deferno.feature.assistant.AssistantWireFormat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The macOS SSE turn-stream transport port the Swift app implements (#282, ADR-0040) — the macOS twin of
 * iOS's `NativeAssistantTransport`. Kotlin owns the **request** (URL, Bearer PAT, JSON body) and the
 * **parsing** ([AssistantWireFormat], shared/unit-tested); Swift only opens a raw `URLSession` byte stream
 * (NSURLSession SSE doesn't buffer like Ktor's Darwin engine), reads SSE frames, and hands each frame's
 * `(eventType, data)` back as plain Strings, then signals completion or failure exactly once. So a backend
 * wire-format change is contained to [AssistantWireFormat]; this file is just the Swift-bridged byte plumbing.
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
 * One turn is a `callbackFlow` (multi-emit): each parsed [AssistantEvent] is sent as it streams; a terminal
 * frame, a clean close, or a transport error each completes the flow **once** (the `completed` guard);
 * `awaitClose` cancels the URLSession task when the collector goes away (e.g. `onCancelTurn`). [token] is
 * read fresh per turn (Active-Account PAT), never cached. Diagnostics route through the uniform os_log
 * facade (ADR-0029) at the Kotlin seam — non-PII only (URL/event-name/frame length, never the token/reply).
 */
class NativeAssistantStream(
    private val transport: NativeAssistantTransport,
    private val baseUrl: String,
    private val token: () -> String?,
) : AssistantStream {

    override fun streamTurn(request: AssistantTurnRequest): Flow<AssistantEvent> = callbackFlow {
        // The transport calls back serially from its single reader task, so a plain flag guards the
        // one-completion invariant. close() is idempotent regardless.
        var completed = false
        val complete: (AssistantEvent?) -> Unit = { terminal ->
            if (!completed) {
                completed = true
                terminal?.let { trySend(it) }
                close()
            }
        }

        val url = AssistantWireFormat.turnUrl(baseUrl, request)
        val authToken = token()
        log.d { "turn POST $url (auth=${if (authToken == null) "none" else "yes"})" }

        val handle = transport.stream(
            url = url,
            authToken = authToken,
            body = AssistantWireFormat.turnBody(request),
            onEvent = { type, data ->
                log.d { "frame event='$type' dataLen=${data.length}" }
                val event = AssistantWireFormat.toEvent(type, data)
                when {
                    event == null -> Unit // unknown/heartbeat frame — ignore
                    event == AssistantEvent.Done -> complete(AssistantEvent.Done)
                    event is AssistantEvent.Error -> complete(event)
                    else -> trySend(event)
                }
            },
            onDone = {
                log.d { "stream closed" }
                complete(AssistantEvent.Done)
            },
            onError = { message ->
                log.w { "stream error: $message" }
                complete(AssistantEvent.Error(message))
            },
        )

        awaitClose { handle.cancel() }
    }

    private companion object {
        val log = Logger("AssistantStream")
    }
}
