package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.outbox.CommentTargets
import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldChange
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val diffJson = Json { ignoreUnknownKeys = true }

/**
 * The typed old->new field diff of a recorded change (#260 follow-up), derived at read-time by zipping the
 * ledger's captured new-value [ActivityEntry.body] and old-value [ActivityEntry.before] JSON per key. Both
 * are the minimal, changed-keys-only objects the outbox/writer produced (ADR-0011), so the key union *is*
 * the set of changed fields. Empty when nothing was captured (a pre-diff row, a delete, or a writer that
 * records no payload) — the View then falls back to the coarse [summaryInfo].
 *
 * Per key: [after] comes from [body], [before] from [before]; a wire `null` reads as
 * [ActivityFieldValue.Cleared] (and an emptied labels array likewise), a key absent from one side reads
 * as [ActivityFieldValue.Unavailable] (e.g. an un-hydrated description's old body). Malformed JSON is
 * swallowed to an empty diff — a diagnostics feature must never crash the screen.
 */
fun ActivityEntry.changes(): List<ActivityFieldChange> {
    // A locally-captured body/before pair is RICHER than the server's `detail` for the same edit: it holds
    // the values this device actually sent, including fields the server's whitelist doesn't snapshot. So
    // the local capture wins where it exists, and `detail` fills in for rows this device never wrote.
    localChanges()?.let { return it }
    return detailChanges()
}

/** The pre-#364 derivation: zip the captured new-value [ActivityEntry.body] and old-value `before`. */
private fun ActivityEntry.localChanges(): List<ActivityFieldChange>? {
    val after = body.parseObjectOrNull()
    val before = before.parseObjectOrNull()
    if (after == null && before == null) return null

    val afterObj = after ?: JsonObject(emptyMap())
    val beforeObj = before ?: JsonObject(emptyMap())
    // Body keys first (the change's own order), then any before-only keys — a stable, meaningful order.
    // `activity` is skipped: the outbox choke-point merges the client-minted stamp into the body that goes
    // on the wire, and it is metadata about the change, not a field the user changed.
    val keys = LinkedHashSet<String>().apply { addAll(afterObj.keys); addAll(beforeObj.keys) }
        .filterNot { it == ACTIVITY_STAMP_KEY }

    return keys.map { key ->
        ActivityFieldChange(
            field = ActivityField.fromKey(key),
            rawKey = key,
            before = beforeObj[key].toFieldValue(),
            after = afterObj[key].toFieldValue(),
        )
    }
}

/**
 * The server-sourced diff, read out of the encrypted-at-rest `detail` blob the reconcile brought back.
 *
 * Two action kinds carry a renderable diff and they are shaped differently:
 * - `updated` → `{"fields": {"<key>": {"old": …, "new": …}}}`, the whitelisted before/after snapshot pair.
 * - `status_changed` → `{"from": …, "to": …}` at the top level, the dedicated "mark done" verb the server
 *   splits out when `status` is the only field that moved.
 *
 * Every other verb says everything in its summary line and returns an empty diff. Order follows the
 * server's own key order, which `json_field_diff` emits sorted, so it is stable across pages.
 */
private fun ActivityEntry.detailChanges(): List<ActivityFieldChange> {
    val obj = detail.parseObjectOrNull() ?: return emptyList()
    return when (actionKind) {
        ActivityActionKind.Updated -> {
            val fields = obj["fields"] as? JsonObject ?: return emptyList()
            fields.map { (key, pair) ->
                val sides = pair as? JsonObject
                ActivityFieldChange(
                    field = ActivityField.fromKey(key),
                    rawKey = key,
                    before = sides?.get("old").toFieldValue(),
                    after = sides?.get("new").toFieldValue(),
                )
            }
        }
        ActivityActionKind.StatusChanged -> {
            // An occurrence status change carries `{to}` or `{cleared:true}` rather than a from/to pair —
            // its summary verb already says which, so there is no diff worth rendering.
            val from = obj["from"]
            val to = obj["to"]
            if (from == null && to == null) {
                emptyList()
            } else {
                listOf(
                    ActivityFieldChange(
                        field = ActivityField.Status,
                        rawKey = "status",
                        before = from.toFieldValue(),
                        after = to.toFieldValue(),
                    ),
                )
            }
        }
        else -> emptyList()
    }
}

/** The lowercase item-kind token the server puts on most `detail` blobs ("task"/"chore"/"habit"/"event"). */
internal fun ActivityEntry.detailItemKind(): String? =
    (detail.parseObjectOrNull()?.get("item_kind") as? JsonPrimitive)?.contentOrNull?.lowercase()

/** Whether an occurrence-scoped `status_changed` was a CLEAR (`{"cleared": true}`) rather than a mark. */
internal fun ActivityEntry.detailCleared(): Boolean =
    (detail.parseObjectOrNull()?.get("cleared") as? JsonPrimitive)?.booleanOrNull == true

/** The body key the outbox choke-point merges the client-minted stamp under — never a user-facing field. */
private const val ACTIVITY_STAMP_KEY = "activity"

private fun String?.parseObjectOrNull(): JsonObject? {
    if (this == null) return null
    return runCatching { diffJson.parseToJsonElement(this) as? JsonObject }.getOrNull()
}

/**
 * The comment text a comment post/edit row carries (#260) — the `"body"` of the captured request JSON on a
 * `comment`/`comment-create` [ActivityEntry.target]. `null` for a non-comment row, a delete (no body), a
 * malformed/blank body, or a **private** comment (`is_private == true`) — the feed never surfaces private
 * text, cheap insurance for a future private-comment UI or a server-sourced reconcile row.
 */
fun ActivityEntry.commentBody(): String? {
    if (!target.startsWith(CommentTargets.CREATE_PREFIX) &&
        !target.startsWith(CommentTargets.EDIT_PREFIX)
    ) {
        return null
    }
    val obj = body.parseObjectOrNull() ?: return null
    if ((obj["is_private"] as? JsonPrimitive)?.booleanOrNull == true) return null
    return (obj["body"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * A captured JSON value → its display side. A missing key ([JsonElement] null) is [ActivityFieldValue
 * .Unavailable] ("not captured"); an explicit wire `null` or an emptied list is [ActivityFieldValue
 * .Cleared] ("emptied"); anything else is its raw content ([JsonArray] joined for labels).
 */
private fun JsonElement?.toFieldValue(): ActivityFieldValue = when (this) {
    null -> ActivityFieldValue.Unavailable
    is JsonNull -> ActivityFieldValue.Cleared
    is JsonArray -> if (isEmpty()) {
        ActivityFieldValue.Cleared
    } else {
        ActivityFieldValue.Present(joinToString(", ") { it.jsonPrimitive.content })
    }
    is JsonPrimitive -> ActivityFieldValue.Present(content)
    else -> ActivityFieldValue.Present(toString())
}
