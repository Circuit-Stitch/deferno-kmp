package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The six wire status enums (ADR-0011, CONTRACT-NOTES → "Status") decode their EXACT wire tokens
 * and degrade an unknown/additive token to `Unknown` via [DefernoJson]'s `coerceInputValues`
 * (which coerces an unrecognised enum token to the **property default** — so every enum-typed DTO
 * field defaults to `...Wire.Unknown`). These tests prove additive backend enum values never crash
 * the tolerant reader.
 */
class WireStatusEnumTest {

    @Serializable
    private data class TaskStatusHolder(val status: TaskStatusWire = TaskStatusWire.Unknown)

    @Serializable
    private data class DefStatusHolder(val status: DefStatusWire = DefStatusWire.Unknown)

    @Serializable
    private data class OccStatusHolder(val status: OccurrenceStatusWire = OccurrenceStatusWire.Unknown)

    @Serializable
    private data class ChoreOccStatusHolder(
        val status: ChoreOccurrenceStatusWire = ChoreOccurrenceStatusWire.Unknown,
    )

    @Serializable
    private data class DerivedChoreOccStatusHolder(
        val status: DerivedChoreOccurrenceStatusWire = DerivedChoreOccurrenceStatusWire.Unknown,
    )

    private inline fun <reified T> decodeStatus(holder: kotlinx.serialization.KSerializer<T>, token: String): T =
        DefernoJson.decodeFromString(holder, """{"status":"$token"}""")

    @Test
    fun taskStatusWireDecodesEveryWireToken() {
        assertEquals(TaskStatusWire.Open, decodeStatus(TaskStatusHolder.serializer(), "open").status)
        assertEquals(TaskStatusWire.InProgress, decodeStatus(TaskStatusHolder.serializer(), "in-progress").status)
        assertEquals(TaskStatusWire.InReview, decodeStatus(TaskStatusHolder.serializer(), "in-review").status)
        assertEquals(TaskStatusWire.Done, decodeStatus(TaskStatusHolder.serializer(), "done").status)
        assertEquals(TaskStatusWire.Dropped, decodeStatus(TaskStatusHolder.serializer(), "dropped").status)
    }

    @Test
    fun taskStatusWireDegradesUnknownTokenToUnknown() {
        assertEquals(TaskStatusWire.Unknown, decodeStatus(TaskStatusHolder.serializer(), "frobnicate").status)
    }

    @Test
    fun defStatusWireDecodesEveryWireToken() {
        assertEquals(DefStatusWire.Active, decodeStatus(DefStatusHolder.serializer(), "active").status)
        assertEquals(DefStatusWire.InReview, decodeStatus(DefStatusHolder.serializer(), "in-review").status)
        assertEquals(DefStatusWire.Archived, decodeStatus(DefStatusHolder.serializer(), "archived").status)
        assertEquals(DefStatusWire.Unknown, decodeStatus(DefStatusHolder.serializer(), "frobnicate").status)
    }

    @Test
    fun occurrenceStatusWireDecodesEveryWireToken() {
        assertEquals(OccurrenceStatusWire.Scheduled, decodeStatus(OccStatusHolder.serializer(), "scheduled").status)
        assertEquals(OccurrenceStatusWire.InProgress, decodeStatus(OccStatusHolder.serializer(), "in_progress").status)
        assertEquals(OccurrenceStatusWire.DoneOnTime, decodeStatus(OccStatusHolder.serializer(), "done_on_time").status)
        assertEquals(OccurrenceStatusWire.DoneLate, decodeStatus(OccStatusHolder.serializer(), "done_late").status)
        assertEquals(OccurrenceStatusWire.Dropped, decodeStatus(OccStatusHolder.serializer(), "dropped").status)
        assertEquals(OccurrenceStatusWire.Unknown, decodeStatus(OccStatusHolder.serializer(), "frobnicate").status)
    }

    @Test
    fun choreOccurrenceStatusWireDecodesEveryWireToken() {
        assertEquals(
            ChoreOccurrenceStatusWire.InProgress,
            decodeStatus(ChoreOccStatusHolder.serializer(), "in_progress").status,
        )
        assertEquals(
            ChoreOccurrenceStatusWire.DoneOnTime,
            decodeStatus(ChoreOccStatusHolder.serializer(), "done_on_time").status,
        )
        assertEquals(
            ChoreOccurrenceStatusWire.DoneLate,
            decodeStatus(ChoreOccStatusHolder.serializer(), "done_late").status,
        )
        assertEquals(
            ChoreOccurrenceStatusWire.Skipped,
            decodeStatus(ChoreOccStatusHolder.serializer(), "skipped").status,
        )
        assertEquals(
            ChoreOccurrenceStatusWire.Unknown,
            decodeStatus(ChoreOccStatusHolder.serializer(), "frobnicate").status,
        )
    }

    @Test
    fun derivedChoreOccurrenceStatusWireDecodesEveryWireToken() {
        val ser = DerivedChoreOccStatusHolder.serializer()
        assertEquals(DerivedChoreOccurrenceStatusWire.Scheduled, decodeStatus(ser, "scheduled").status)
        assertEquals(DerivedChoreOccurrenceStatusWire.Missed, decodeStatus(ser, "missed").status)
        assertEquals(DerivedChoreOccurrenceStatusWire.InProgress, decodeStatus(ser, "in_progress").status)
        assertEquals(DerivedChoreOccurrenceStatusWire.DoneOnTime, decodeStatus(ser, "done_on_time").status)
        assertEquals(DerivedChoreOccurrenceStatusWire.DoneLate, decodeStatus(ser, "done_late").status)
        assertEquals(DerivedChoreOccurrenceStatusWire.Skipped, decodeStatus(ser, "skipped").status)
        assertEquals(DerivedChoreOccurrenceStatusWire.Unknown, decodeStatus(ser, "frobnicate").status)
    }
}
