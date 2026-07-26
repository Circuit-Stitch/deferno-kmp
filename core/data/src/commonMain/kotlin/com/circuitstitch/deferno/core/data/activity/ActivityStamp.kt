package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val stampJson = Json { ignoreUnknownKeys = true }

/** The advisory `source` this client asserts on every write it originates. */
private const val WIRE_SOURCE_MOBILE = "mobile"

/**
 * A client-minted Activity-ledger stamp (#364): the `activity: { id, at, source }` sibling the backend
 * accepts on 36 mutation routes.
 *
 * [entryId] is **the merge key, and the whole point**. The server files its authoritative ledger row under
 * whatever id the client supplies, so an optimistic row recorded at apply-time and the row that comes back
 * on the next `?since=` reconcile share an identity — and the reconcile *replaces* rather than duplicates.
 * Mint it once at enqueue time and it stays stable across every outbox replay, which also makes the
 * server-side insert (`ON CONFLICT (entry_id) DO NOTHING`) idempotent under retry.
 *
 * [occurredAt] is the client wall-clock the action happened. It is what the feed sorts by, and it is the
 * reason offline work reads correctly: a phone that acts at 09:00 and flushes its outbox at 17:00 must show
 * the change at 09:00, not "popping up" hours later at server-receive time. The server keeps its own
 * `observed_at` alongside as the forensic anchor, so asserting a client time is safe — time is soft,
 * attribution is hard.
 */
data class ActivityStamp(
    val entryId: String,
    val occurredAt: Instant,
) {
    /** The wire object merged into a mutation body under the `activity` key. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", entryId)
        put("at", occurredAt.toString())
        put("source", WIRE_SOURCE_MOBILE)
    }

    companion object {
        /** Mint a fresh stamp for an action applied at [now]. */
        @OptIn(ExperimentalUuidApi::class)
        fun mint(now: Instant): ActivityStamp = ActivityStamp(Uuid.random().toString(), now)
    }
}

/**
 * This request with [stamp] merged in under `activity`, or unchanged when the route can't carry one.
 *
 * Merges into the **parsed** body rather than string-splicing it, so an intent-shaped minimal body keeps
 * its exact keys and explicit nulls (ADR-0011's "never emit an absent field" rule is untouched — this only
 * ever *adds* a sibling). A body that is absent or isn't a JSON object is left alone: a null body sends no
 * HTTP entity at all, and there is nothing to merge into.
 *
 * Whether the route can carry one is **not** re-derived here — it is [OutboxRequest.acceptsActivityStamp],
 * declared by whoever picked the route. Re-reading that field costs nothing and keeps the merge total: a
 * caller that stamps unconditionally still cannot push an unexpected key onto a strict payload and get the
 * write dead-lettered.
 */
fun OutboxRequest.withActivityStamp(stamp: ActivityStamp): OutboxRequest {
    if (!acceptsActivityStamp) return this
    val existing = body?.let { raw ->
        runCatching { stampJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    } ?: return this
    val merged = buildJsonObject {
        for ((k, v) in existing) put(k, v)
        put("activity", stamp.toJson())
    }
    return copy(body = merged.toString())
}
