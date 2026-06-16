package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.network.DefernoJson
import io.ktor.client.HttpClient
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
 */
class KtorOutboxRequestSender(
    private val client: HttpClient,
) : OutboxRequestSender {

    override suspend fun send(request: OutboxRequest): SendOutcome {
        val status = try {
            client.request {
                method = request.method.toKtor()
                url { appendPathSegments(request.path) }
                request.body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
            }.status.value
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (_: Throwable) {
            // No usable response: connection / timeout / TLS, or a blocked cleartext request. Transient
            // from the queue's point of view — retry (a permanent misconfig is bounded by max-attempts).
            return SendOutcome.Retryable
        }
        return outcomeFor(status)
    }

    override suspend fun sendCreate(request: OutboxRequest): CreateSendOutcome {
        val response = try {
            client.request {
                method = request.method.toKtor()
                url { appendPathSegments(request.path) }
                request.body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return CreateSendOutcome.Retryable // no usable response — transient (bounded by max-attempts)
        }
        return when (outcomeFor(response.status.value)) {
            // The create succeeded server-side; read back the assigned id (blank if unparseable → the
            // listener treats it as "honored the client id", no heal). A failed body read can't undo the
            // server-side create, so it is still a success with a blank id.
            SendOutcome.Success -> CreateSendOutcome.Created(runCatching { response.parseCreatedId() }.getOrNull().orEmpty())
            SendOutcome.Retryable -> CreateSendOutcome.Retryable
            SendOutcome.Terminal -> CreateSendOutcome.Terminal
        }
    }
}

/** Pulls the server-assigned id out of the create envelope (`{version, data:{id}}`); null if absent. */
private suspend fun io.ktor.client.statement.HttpResponse.parseCreatedId(): String? =
    DefernoJson.decodeFromString<CreatedIdEnvelope>(bodyAsText()).data?.id

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
