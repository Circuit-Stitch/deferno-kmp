package com.circuitstitch.deferno.core.agent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient JSON for raw-text engine output: a small on-device model may add fields the schema doesn't
 * name ([ignoreUnknownKeys]) or relax quoting ([isLenient]); the schema still rejects anything that
 * can't decode to [T] after [coerceToSchema] has normalised it.
 */
internal val RawInferenceJson: Json = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

/**
 * Decode the **raw text** a plain-text [InferenceEngine] returned (the on-device FoundationModels
 * engine, ADR-0029 Phase 3) into a validated [InferenceResult]. An engine whose output isn't natively
 * schema-constrained hands back free text; this strips any surrounding prose / ```json fence down to
 * the first balanced JSON object, **coerces it toward the schema** ([coerceToSchema]), then decodes
 * against this schema's [serializer] — so validation stays Kotlin-owned (the propose-only contract,
 * ADR-0027) and a parse failure is the typed [InferenceResult.Failure.MalformedOutput], never a throw.
 *
 * The Koog engine doesn't use this — Koog's structured output already returns a decoded value.
 */
fun <T : Any> InferenceSchema<T>.parse(
    rawText: String,
    json: Json = RawInferenceJson,
): InferenceResult<T> {
    val obj = extractJsonObject(rawText)
        ?: return InferenceResult.Failure.MalformedOutput("no JSON object in model output")
    return try {
        val coerced = coerceToSchema(json.parseToJsonElement(obj), serializer.descriptor)
        InferenceResult.Success(json.decodeFromJsonElement(serializer, coerced))
    } catch (e: SerializationException) {
        // Class name ONLY — the message can quote the malformed model output, which must never leak
        // into a loggable detail (the privacy invariant on InferenceEngine).
        InferenceResult.Failure.MalformedOutput(e::class.simpleName ?: "deserialization failure")
    } catch (e: IllegalArgumentException) {
        InferenceResult.Failure.MalformedOutput(e::class.simpleName ?: "decode failure")
    }
}

/**
 * Normalise a model's JSON toward what the [descriptor] expects, **walking the two together** so the
 * coercion is generic (no schema names appear here) — adapt the parser to how the model naturally
 * writes, rather than forcing the model to match the schema (which a small model does unreliably):
 *
 * - a date/time field whose value carries extra precision (`2026-06-14T16:00:00-07:00`) is trimmed to
 *   the part the type wants (`2026-06-14`) — the field's own type says which part;
 * - a number written as a quoted string (`"0.8"`) is unquoted to a JSON number;
 * - a "no value" placeholder (`"none"`, `"null"`) in a **nullable** field becomes JSON `null`.
 *
 * Unknown keys are passed through untouched ([RawInferenceJson] drops them on decode).
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun coerceToSchema(element: JsonElement, descriptor: SerialDescriptor): JsonElement = when {
    element is JsonNull -> element
    element is JsonArray && descriptor.kind == StructureKind.LIST -> {
        val item = descriptor.getElementDescriptor(0)
        JsonArray(element.map { coerceToSchema(it, item) })
    }
    element is JsonObject && (descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) ->
        JsonObject(
            element.mapValues { (key, value) ->
                val index = descriptor.getElementIndex(key)
                if (index >= 0) coerceToSchema(value, descriptor.getElementDescriptor(index)) else value
            },
        )
    element is JsonPrimitive -> coercePrimitive(element, descriptor)
    else -> element
}

@OptIn(ExperimentalSerializationApi::class)
private fun coercePrimitive(primitive: JsonPrimitive, descriptor: SerialDescriptor): JsonElement {
    // A placeholder the model writes for "no value" → JSON null, but only where the field allows it.
    if (descriptor.isNullable && primitive.isString && primitive.content.trim().lowercase() in NULL_PLACEHOLDERS) {
        return JsonNull
    }
    val typeName = descriptor.serialName.removeSuffix("?") // a nullable descriptor's name ends in '?'
    when {
        typeName.endsWith("LocalDateTime") -> return JsonPrimitive(primitive.content.trim())
        typeName.endsWith("LocalDate") -> return JsonPrimitive(primitive.content.datePart())
        typeName.endsWith("LocalTime") -> return JsonPrimitive(primitive.content.timePart())
        typeName.endsWith("Instant") -> return JsonPrimitive(primitive.content.trim())
    }
    // A quoted number where the schema wants a number → unquote it.
    if (primitive.isString) {
        when (descriptor.kind) {
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                primitive.content.toDoubleOrNull()?.let { return JsonPrimitive(it) }
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                primitive.content.toLongOrNull()?.let { return JsonPrimitive(it) }
            PrimitiveKind.BOOLEAN ->
                primitive.content.toBooleanStrictOrNull()?.let { return JsonPrimitive(it) }
            else -> {}
        }
    }
    return primitive
}

private val NULL_PLACEHOLDERS = setOf("none", "null", "n/a", "")

/** The calendar-date part of a model's date value: drop any time/zone after a `T` (or a space). */
private fun String.datePart(): String = trim().substringBefore('T').substringBefore(' ').trim()

/** The clock-time part of a model's time value: what follows a `T` if present, else the whole string. */
private fun String.timePart(): String = trim().substringAfter('T').trim()

/**
 * The first balanced top-level `{ … }` object in [text], or `null` if there is none. Tracks string
 * literals (and their escapes) so a `}` inside a value — e.g. a task title — doesn't end the object
 * early; this lets a leading ```json fence or a "Here is the JSON:" preamble fall away.
 *
 * ponytail: a naive single-object scan — enough for one well-instructed on-device model returning one
 * object. Swap for a streaming JSON reader if engines start returning a root array or multiple objects.
 */
internal fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}
