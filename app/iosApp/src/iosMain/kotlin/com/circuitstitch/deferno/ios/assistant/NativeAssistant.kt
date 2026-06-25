package com.circuitstitch.deferno.ios.assistant

import com.circuitstitch.deferno.feature.assistant.AssistantEvent
import com.circuitstitch.deferno.feature.assistant.AssistantStream
import com.circuitstitch.deferno.feature.assistant.AssistantTurnRequest
import com.circuitstitch.deferno.feature.assistant.AssistantWireFormat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import software.amazon.app.kmplogger.logger

/**
 * The iOS SSE turn-stream transport port the Swift app implements (#282, ADR-0040) — the iOS twin of the
 * native inference/transcriber seams (ADR-0037). Kotlin owns the **request** (URL, Bearer PAT, JSON body)
 * and the **parsing**; Swift only opens a raw `URLSession` byte stream (NSURLSession SSE doesn't buffer like
 * Ktor's Darwin engine, ADR-0040), reads SSE frames, and hands each frame's `(eventType, data)` back as
 * plain Strings, then signals completion or failure exactly once. So a backend wire-format change is
 * contained to [AssistantWireFormat] (URL/body/frame mapping) — pure, common, unit-tested (the ADR's
 * "isolate the wire" guarantee); this file is just the Swift-bridged byte plumbing.
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

        val url = AssistantWireFormat.turnUrl(baseUrl, request)
        val authToken = token()
        // Diagnostics route through the shared kmp-logger → os_log (DEBUG, so Release filters them out).
        // Non-PII only: the URL/event-name/frame length, never the Bearer token or the reply/message text.
        AssistantStreamLog.logger.d { "turn POST $url (auth=${if (authToken == null) "none" else "yes"})" }

        val handle = transport.stream(
            url = url,
            authToken = authToken,
            body = AssistantWireFormat.turnBody(request),
            onEvent = { type, data ->
                // Per-frame trace — the diagnostic that surfaced the CRLF frame-merge bug (event + length).
                AssistantStreamLog.logger.d { "frame event='$type' dataLen=${data.length}" }
                val event = AssistantWireFormat.toEvent(type, data)
                when {
                    event == null -> Unit // unknown/heartbeat frame — ignore
                    event == AssistantEvent.Done -> complete(AssistantEvent.Done)
                    event is AssistantEvent.Error -> complete(event)
                    else -> trySend(event)
                }
            },
            onDone = {
                AssistantStreamLog.logger.d { "stream closed" }
                complete(AssistantEvent.Done)
            },
            onError = { message ->
                AssistantStreamLog.logger.w { "stream error: $message" }
                complete(AssistantEvent.Error(message))
            },
        )

        awaitClose { handle.cancel() }
    }
}

// kmp-logger's `logger` is an Any-receiver extension; a tag object gives the stream's `callbackFlow`
// (whose receiver is ProducerScope, not the class) a stable tag ("Deferno: AssistantStreamLog").
private object AssistantStreamLog
