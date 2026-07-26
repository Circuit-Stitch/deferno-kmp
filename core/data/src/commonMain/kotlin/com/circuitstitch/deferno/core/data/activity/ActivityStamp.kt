package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
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
 * Whether this request's route accepts the `activity` ingest field.
 *
 * Deliberately a **whitelist derived from the pinned contract**, not a guess: sending an unexpected key to
 * a route with a strict payload is a `422`, which the outbox sender treats as Terminal and dead-letters —
 * so a false positive here would silently destroy the user's write, not merely lose an audit row. When in
 * doubt the answer is `false`: the cost is a server-minted entry id (fine), rather than a lost mutation.
 *
 * The routes that pointedly do NOT accept it, and why:
 * - `PATCH auth/me/settings` — a user-preferences write, not an [com.circuitstitch.deferno.core.model.Item]
 *   mutation; the ledger only witnesses item actions.
 * - `DELETE tasks/{id}` / `DELETE items/{id}` — still bodiless deletes upstream (the ADR's soft-delete
 *   migration covered comments, attachments and occurrence-clears, but not item delete), so the server
 *   mints those entry ids and the optimistic row is superseded rather than merged.
 */
fun OutboxRequest.acceptsActivityStamp(): Boolean {
    val p = path
    return when {
        // Creates: POST {tasks|habits|chores|events}
        method == OutboxMethod.Post && p.size == 1 && p[0] in ITEM_COLLECTIONS -> true
        // Item edits: PATCH {tasks|habits|chores|events}/{id}
        method == OutboxMethod.Patch && p.size == 2 && p[0] in ITEM_COLLECTIONS -> true
        // Plan: POST tasks/plan/{add|remove|reorder}
        method == OutboxMethod.Post && p.size == 3 && p[0] == "tasks" && p[1] == "plan" -> true
        // Move: POST {tasks|items}/{id}/move
        method == OutboxMethod.Post && p.size == 3 && p[0] in setOf("tasks", "items") && p[2] == "move" -> true
        // Convert: POST items/{id}/convert
        method == OutboxMethod.Post && p.size == 3 && p[0] == "items" && p[2] == "convert" -> true
        // Comments: POST tasks/{id}/comments · PATCH comments/{id} · POST comments/{id}/delete
        method == OutboxMethod.Post && p.size == 3 && p[0] == "tasks" && p[2] == "comments" -> true
        method == OutboxMethod.Patch && p.size == 2 && p[0] == "comments" -> true
        method == OutboxMethod.Post && p.size == 3 && p[0] == "comments" && p[2] == "delete" -> true
        // Attachments: POST items/{id}/attachments · PATCH items/{id}/attachments/{aid}
        //              POST items/{id}/attachments/{aid}/delete   (NOT …/presign)
        p.size >= 3 && p[0] == "items" && p[2] == "attachments" -> attachmentAcceptsStamp(p)
        // Occurrences: POST habits/{id}/occurrences · PUT chores/{id}/occurrences/{date}
        //              POST events/{id}/occurrences/{date} · POST …/{date}/{clear|reschedule}
        p.size >= 3 && p[0] in RECURRING_COLLECTIONS && p[2] == "occurrences" -> occurrenceAcceptsStamp(p)
        else -> false
    }
}

private val ITEM_COLLECTIONS = setOf("tasks", "habits", "chores", "events")
private val RECURRING_COLLECTIONS = setOf("habits", "chores", "events")

/** `items/{id}/attachments…` — everything but the presign handshake, which mints no ledger entry. */
private fun OutboxRequest.attachmentAcceptsStamp(p: List<String>): Boolean = when {
    p.size == 4 && p[3] == "presign" -> false
    method == OutboxMethod.Post && p.size == 3 -> true // commit
    method == OutboxMethod.Patch && p.size == 4 -> true // caption
    method == OutboxMethod.Post && p.size == 5 && p[4] == "delete" -> true
    else -> false
}

/** `{habits|chores|events}/{id}/occurrences…` — mark, clear and reschedule; the list GET has no body. */
private fun OutboxRequest.occurrenceAcceptsStamp(p: List<String>): Boolean = when {
    method == OutboxMethod.Post && p.size == 3 -> true // POST habits/{id}/occurrences
    method == OutboxMethod.Put && p.size == 4 -> true // PUT chores/{id}/occurrences/{date}
    method == OutboxMethod.Post && p.size == 4 -> true // POST events/{id}/occurrences/{date}
    method == OutboxMethod.Post && p.size == 5 && p[4] in setOf("clear", "reschedule") -> true
    else -> false
}

/**
 * This request with [stamp] merged in under `activity`, or unchanged when the route can't carry one.
 *
 * Merges into the **parsed** body rather than string-splicing it, so an intent-shaped minimal body keeps
 * its exact keys and explicit nulls (ADR-0011's "never emit an absent field" rule is untouched — this only
 * ever *adds* a sibling). A body that is absent or isn't a JSON object is left alone: a null body sends no
 * HTTP entity at all, and there is nothing to merge into.
 */
fun OutboxRequest.withActivityStamp(stamp: ActivityStamp): OutboxRequest {
    if (!acceptsActivityStamp()) return this
    val existing = body?.let { raw ->
        runCatching { stampJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    } ?: return this
    val merged = buildJsonObject {
        for ((k, v) in existing) put(k, v)
        put("activity", stamp.toJson())
    }
    return copy(body = merged.toString())
}
