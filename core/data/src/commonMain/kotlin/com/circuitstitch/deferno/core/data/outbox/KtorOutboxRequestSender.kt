package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.network.DefernoJson
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * The production [OutboxRequestSender] over the shared Deferno [HttpClient] (#17/#23, ADR-0001). It
 * replays an [OutboxRequest] as a raw request — **not** via `requestApi` — on purpose: a mutation's
 * response is uninteresting (a `204` no-content DELETE, or an envelope we'd only discard), so this
 * inspects the HTTP status directly and never tries to parse a body/version. The shared client's
 * bearer-auth + cleartext-guard plugins fire on every request (they hook `onRequest`), so the replay
 * carries the Active Account credential and the TLS guard just like every other call (#17).
 *
 * **Verbatim body (idempotency).** The stored [OutboxRequest.body] is sent as a [TextContent] with an
 * `application/json` content type, bypassing content-negotiation re-encoding — the exact bytes built
 * at enqueue time go on the wire, so a replay is byte-identical to the original (#23).
 *
 * **Status → [SendOutcome]** (see [SendOutcome] for the rationale): `2xx`/`404` → [SendOutcome.Success];
 * `401`/`408`/`429`/`5xx` and any transport failure → [SendOutcome.Retryable]; every other status
 * (other `4xx`, an unexpected `3xx`) → [SendOutcome.Terminal].
 *
 * **Network logging.** Every non-success replay is logged with its method, path, HTTP status, and the
 * server's response body (truncated). This is the diagnostic seam that makes a bad request/contract
 * visible instead of silently vanishing — a terminally-rejected write is about to be dead-lettered by the
 * [OutboxProcessor], and the logged status+body is what tells you *why* (e.g. a `422` naming a missing
 * required field). Successes log at debug only; a transport failure (no response) logs at warn.
 */
class KtorOutboxRequestSender(
    private val client: HttpClient,
) : OutboxRequestSender {

    private val log = Logger("OutboxSender")

    override suspend fun send(request: OutboxRequest): SendOutcome {
        val response = try {
            client.request { dispatch(request) }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Throwable) {
            // No usable response: connection / timeout / TLS, or a blocked cleartext request. Transient
            // from the queue's point of view — retry (a permanent misconfig is bounded by max-attempts).
            log.w(e) { "${request.method} ${request.displayPath()} failed with no response → retry" }
            return SendOutcome.Retryable
        }
        val status = response.status.value
        val outcome = outcomeFor(status)
        if (outcome == SendOutcome.Success) log.d { "${request.method} ${request.displayPath()} → $status" }
        else logFailure(request, status, response.readBodyOrNull(), outcome)
        return outcome
    }

    override suspend fun sendCreate(request: OutboxRequest): CreateSendOutcome {
        val response = try {
            client.request { dispatch(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "${request.method} ${request.displayPath()} (create) failed with no response → retry" }
            return CreateSendOutcome.Retryable // no usable response — transient (bounded by max-attempts)
        }
        val status = response.status.value
        // Read the body once and reuse it for both the id parse (success) and the failure log (rejection).
        val body = response.readBodyOrNull()
        return when (val outcome = outcomeFor(status)) {
            // The create succeeded server-side; read back the assigned id (blank if unparseable → the
            // listener treats it as "honored the client id", no heal). A failed body read can't undo the
            // server-side create, so it is still a success with a blank id.
            SendOutcome.Success -> {
                log.d { "${request.method} ${request.displayPath()} → $status (create)" }
                CreateSendOutcome.Created(body?.let { runCatching { it.parseCreatedId() }.getOrNull() }.orEmpty())
            }
            SendOutcome.Retryable -> {
                logFailure(request, status, body, outcome)
                CreateSendOutcome.Retryable
            }
            SendOutcome.Terminal -> {
                logFailure(request, status, body, outcome)
                CreateSendOutcome.Terminal
            }
        }
    }

    /** Method + URL + verbatim body — the shared request shape for both the plain and create dispatch. */
    private fun HttpRequestBuilder.dispatch(request: OutboxRequest) {
        method = request.method.toKtor()
        url { appendPathSegments(request.path) }
        request.body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
    }

    /** Log a non-success replay with the server's status + body — the "why it will/won't retry" record. */
    private fun logFailure(request: OutboxRequest, status: Int, body: String?, outcome: SendOutcome) {
        val head = "${request.method} ${request.displayPath()} → HTTP $status"
        val detail = body?.takeIf { it.isNotBlank() }?.let { ": ${it.take(BODY_LOG_LIMIT)}" }.orEmpty()
        when (outcome) {
            SendOutcome.Terminal -> log.e { "$head — terminal, dead-lettering (preserved, will not sync)$detail" }
            SendOutcome.Retryable -> log.w { "$head — retryable, backing off$detail" }
            SendOutcome.Success -> {} // logged by the callers at debug
        }
    }

    private companion object {
        /** Cap on how much of a server error body we log — enough for an error envelope, not a huge page. */
        const val BODY_LOG_LIMIT = 500
    }
}

/** `/`-joined path for logs (segments never contain `/`), e.g. `/tasks/{id}/comments`. */
private fun OutboxRequest.displayPath(): String = "/" + path.joinToString("/")

/** Best-effort body read for logging/parsing — a consumed/failed stream degrades to `null`, never throws. */
private suspend fun io.ktor.client.statement.HttpResponse.readBodyOrNull(): String? =
    runCatching { bodyAsText() }.getOrNull()

/** Pulls the server-assigned id out of the create envelope (`{version, data:{id}}`); null if absent. */
private fun String.parseCreatedId(): String? =
    DefernoJson.decodeFromString<CreatedIdEnvelope>(this).data?.id

@Serializable private data class CreatedIdEnvelope(val data: CreatedId? = null)

@Serializable private data class CreatedId(val id: String? = null)

/** Maps an HTTP status code to the queue's three-way outcome (see [SendOutcome]). */
internal fun outcomeFor(status: Int): SendOutcome = when {
    status in 200..299 -> SendOutcome.Success
    status == 404 -> SendOutcome.Success // already gone — idempotent under LWW
    status == 401 || status == 408 || status == 429 -> SendOutcome.Retryable
    status in 500..599 -> SendOutcome.Retryable
    else -> SendOutcome.Terminal // other 4xx (server rejected the intent) / unexpected 3xx
}

private fun OutboxMethod.toKtor(): HttpMethod = when (this) {
    OutboxMethod.Patch -> HttpMethod.Patch
    OutboxMethod.Post -> HttpMethod.Post
    OutboxMethod.Put -> HttpMethod.Put
    OutboxMethod.Delete -> HttpMethod.Delete
}
