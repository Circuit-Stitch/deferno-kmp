package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldChange
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    val after = body.parseObjectOrNull()
    val before = before.parseObjectOrNull()
    if (after == null && before == null) return emptyList()

    val afterObj = after ?: JsonObject(emptyMap())
    val beforeObj = before ?: JsonObject(emptyMap())
    // Body keys first (the change's own order), then any before-only keys — a stable, meaningful order.
    val keys = LinkedHashSet<String>().apply { addAll(afterObj.keys); addAll(beforeObj.keys) }

    return keys.map { key ->
        ActivityFieldChange(
            field = ActivityField.fromKey(key),
            rawKey = key,
            before = beforeObj[key].toFieldValue(),
            after = afterObj[key].toFieldValue(),
        )
    }
}

private fun String?.parseObjectOrNull(): JsonObject? {
    if (this == null) return null
    return runCatching { diffJson.parseToJsonElement(this) as? JsonObject }.getOrNull()
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
