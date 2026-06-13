package com.circuitstitch.deferno.core.agent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

/**
 * A compact **JSON shape skeleton** for this schema, derived from the [serializer] descriptor: an
 * example-shaped string that names every field and its type token (`"id":"string"`,
 * `"completeBy":"yyyy-mm-dd?"`, arrays as `[…]`, `?` = nullable). It is what a *plain-text* engine that
 * can't constrain output natively (the on-device FoundationModels engine, ADR-0029 Phase 3) is steered
 * with, so the model emits the exact key names and types the [serializer] then validates — closing the
 * gap a name-only prompt leaves (a small model otherwise guesses the root key, uses integer ids, or
 * puts a time in a date field). Generic: the date/time format comes from the field's own type, so no
 * per-schema knowledge lives here.
 *
 * Not a formal JSON Schema — a terse by-example shape is clearer to a small model and far less code.
 */
fun InferenceSchema<*>.jsonSkeleton(): String = serializer.descriptor.skeleton()

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.skeleton(): String = when (kind) {
    StructureKind.LIST -> "[${getElementDescriptor(0).typeToken()}]"
    StructureKind.MAP -> "{\"<key>\":${getElementDescriptor(1).typeToken()}}"
    StructureKind.CLASS, StructureKind.OBJECT ->
        (0 until elementsCount).joinToString(",", prefix = "{", postfix = "}") { i ->
            val nullable = if (getElementDescriptor(i).isNullable) "?" else ""
            "\"${getElementName(i)}\":${getElementDescriptor(i).typeToken(nullable)}"
        }
    else -> typeToken()
}

/** The leaf/inline token for a descriptor — a nested object/array expands; a scalar names its type. */
@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.typeToken(nullableSuffix: String = ""): String = when (kind) {
    StructureKind.CLASS, StructureKind.OBJECT, StructureKind.LIST, StructureKind.MAP -> skeleton()
    SerialKind.ENUM -> "\"${(0 until elementsCount).joinToString("|") { getElementName(it) }}$nullableSuffix\""
    else -> "\"${scalarToken()}$nullableSuffix\""
}

/** A human-clear scalar token; date/time types advertise their wire format so the model matches it. */
@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.scalarToken(): String = when {
    // A nullable type's descriptor serialName carries a trailing '?' — normalize before matching.
    serialName.removeSuffix("?").endsWith("LocalDateTime") -> "yyyy-mm-ddTHH:MM"
    serialName.removeSuffix("?").endsWith("LocalDate") -> "yyyy-mm-dd"
    serialName.removeSuffix("?").endsWith("LocalTime") -> "HH:MM"
    serialName.removeSuffix("?").endsWith("Instant") -> "ISO-8601-datetime"
    else -> when (kind) {
        PrimitiveKind.BOOLEAN -> "boolean"
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        PolymorphicKind.OPEN, PolymorphicKind.SEALED -> "object"
        else -> "string"
    }
}
