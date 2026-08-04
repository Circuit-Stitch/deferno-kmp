package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.ChoreOccurrenceDto
import com.circuitstitch.deferno.core.network.dto.DerivedChoreOccurrenceStatusWire
import com.circuitstitch.deferno.core.network.dto.EventOccurrenceDto
import com.circuitstitch.deferno.core.network.dto.HabitOccurrenceDto
import com.circuitstitch.deferno.core.network.dto.OccurrenceStatusWire
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The three per-kind occurrence-list DTOs → [com.circuitstitch.deferno.core.model.OccurrenceFact]
 * (#390, ADR-0053). Every payload below is copied from the **Rust** struct that actually serialises
 * it, not from webui's TypeScript mirror (`webui/src/types/index.ts` omits `segment_id` entirely) and
 * not from the vendored OpenAPI document, which is stale on all three shapes.
 *
 * The decode itself is the assertion in each of the first three tests: these run through the real
 * [DefernoJson], so a wrong `@SerialName`, a missing required key or a field the tolerant reader
 * cannot place fails here rather than in production on the first sync.
 */
class OccurrenceFactMapperTest {

    // ── habit ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun habitOccurrenceDecodesAndAnUntickedFiringIsAScheduledFact() {
        // `HabitOccurrence { habit_id, date, done_at }` — the whole struct. `done_at` carries no
        // skip_serializing_if, so it is present-and-null rather than absent.
        val json = """{"habit_id":"hab-3","date":"2026-05-17","done_at":null}"""
        val dto: HabitOccurrenceDto = DefernoJson.decodeFromString(json)

        assertEquals("hab-3", dto.habitId)
        assertEquals("2026-05-17", dto.date)
        assertNull(dto.doneAt)

        val fact = dto.toFact(ItemKind.Habit, definitionId = "hab-3-item")

        assertEquals(ItemKind.Habit, fact.kind)
        // The Head the caller addressed, NOT the row's own habit_id (which is the owning Segment
        // after a rule change, #574) — the write path keys on the item id.
        assertEquals("hab-3-item", fact.definitionId)
        assertEquals(LocalDate(2026, 5, 17), fact.date)
        // A row exists that records no progress: a Scheduled *fact*, never the derived reading.
        assertEquals(OccurrenceResolution.Scheduled, fact.resolution)
        assertNull(fact.doneAt)
        assertNull(fact.completeBy)
    }

    @Test
    fun tickedHabitFiringWithNoDeadlineIsDoneOnTime() {
        val json = """{"habit_id":"hab-3","date":"2026-05-17","done_at":"2026-05-17T22:10:00Z"}"""
        val dto: HabitOccurrenceDto = DefernoJson.decodeFromString(json)

        val fact = dto.toFact(ItemKind.Habit, definitionId = "hab-3-item")

        // With nothing to be late against, the only honest reading is on time.
        assertEquals(OccurrenceResolution.DoneOnTime, fact.resolution)
        assertEquals(Instant.parse("2026-05-17T22:10:00Z"), fact.doneAt)
    }

    @Test
    fun tickedHabitFiringPastItsDeadlineIsDoneLate_andTheBoundIsInclusive() {
        // The habit wire row has no deadline of its own, so the caller supplies the one that applied
        // to this date; the punctuality split is then the shared `completionResolution`, whose bound
        // mirrors the backend's decide_chore_done_status (occurrences.rs:1164-1173) and is INCLUSIVE.
        val deadline = Instant.parse("2026-05-17T21:00:00Z")
        val late = HabitOccurrenceDto(habitId = "hab-3", date = "2026-05-17", doneAt = "2026-05-17T21:00:01Z")
        val exactlyOnTheBound = late.copy(doneAt = "2026-05-17T21:00:00Z")

        assertEquals(
            OccurrenceResolution.DoneLate,
            late.toFact(ItemKind.Habit, "hab-3-item", completeBy = deadline).resolution,
        )
        // done_at == complete_by is ON TIME.
        assertEquals(
            OccurrenceResolution.DoneOnTime,
            exactlyOnTheBound.toFact(ItemKind.Habit, "hab-3-item", completeBy = deadline).resolution,
        )
        // The deadline that applied is kept on the fact — the definition's live one is a moving cursor.
        assertEquals(deadline, late.toFact(ItemKind.Habit, "hab-3-item", completeBy = deadline).completeBy)
    }

    // ── chore ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun choreOccurrenceViewDecodesItsFiveFieldsAndCondensesToAFact() {
        // `ChoreOccurrenceView { scheduled_date, status, complete_by, completed_at, segment_id }` —
        // five fields, no id and no parent_id. `segment_id` is on the wire but absent from webui's
        // mirror, which is why this payload is copied from the Rust.
        val json = """
            {
              "scheduled_date": "2026-05-17",
              "status": "done_late",
              "complete_by": "2026-05-17T21:00:00Z",
              "completed_at": "2026-05-18T07:30:00Z",
              "segment_id": "chore-seg-2"
            }
        """.trimIndent()
        val dto: ChoreOccurrenceDto = DefernoJson.decodeFromString(json)

        assertEquals(DerivedChoreOccurrenceStatusWire.DoneLate, dto.status)
        assertEquals("chore-seg-2", dto.segmentId)

        val fact = dto.toFact(ItemKind.Chore, definitionId = "chore-head-1")!!

        assertEquals(ItemKind.Chore, fact.kind)
        // Keyed on the Head, not the Segment the row names.
        assertEquals("chore-head-1", fact.definitionId)
        assertEquals(LocalDate(2026, 5, 17), fact.date)
        // The server already decided punctuality; it is condensed, never recomputed.
        assertEquals(OccurrenceResolution.DoneLate, fact.resolution)
        // `completed_at` is the chore spelling of the other two kinds' `done_at`.
        assertEquals(Instant.parse("2026-05-18T07:30:00Z"), fact.doneAt)
        assertEquals(Instant.parse("2026-05-17T21:00:00Z"), fact.completeBy)
    }

    @Test
    fun aDerivedChoreStatusIsAServerOpinionAboutTodayAndYieldsNoFactAtAll() {
        // `scheduled` means "no record exists" and `missed` is a reading the server took against
        // Utc::now() (occurrences.rs:465/:488-495). Storing either would assert something untrue —
        // that a row exists, or that a UTC verdict is the user's local one. Both yield no row, and
        // the reading is derived at render time instead.
        val base = ChoreOccurrenceDto(
            scheduledDate = "2026-05-17",
            status = DerivedChoreOccurrenceStatusWire.Scheduled,
            completeBy = "2026-05-17T21:00:00Z",
            segmentId = "chore-seg-2",
        )
        assertNull(base.toFact(ItemKind.Chore, "chore-head-1"))
        assertNull(base.copy(status = DerivedChoreOccurrenceStatusWire.Missed).toFact(ItemKind.Chore, "chore-head-1"))

        // The stored four DO yield a fact.
        assertTrue(base.copy(status = DerivedChoreOccurrenceStatusWire.Skipped).toFact(ItemKind.Chore, "c") != null)
    }

    @Test
    fun theChoreEndpointEmitsSkippedAndNeverDropped() {
        // The spelling split is per-endpoint, and one mapper accepts both because the two enums are
        // modelled separately: the chore view emits `skipped`; `dropped` is only a serde alias the
        // v0.1 backend wrote, and is what the EVENT endpoint emits instead (see the event test).
        val skipped = """
            {"scheduled_date":"2026-05-17","status":"skipped","complete_by":"2026-05-17T21:00:00Z","segment_id":"s"}
        """.trimIndent()
        val dto: ChoreOccurrenceDto = DefernoJson.decodeFromString(skipped)
        assertEquals(DerivedChoreOccurrenceStatusWire.Skipped, dto.status)
        assertEquals(OccurrenceResolution.Skipped, dto.toFact(ItemKind.Chore, "chore-head-1")?.resolution)

        // `dropped` is NOT a chore-view token: it coerces to Unknown, which degrades to a Scheduled
        // *fact* rather than to null — an additive token must never be derived into a Missed.
        val dropped = skipped.replace("\"skipped\"", "\"dropped\"")
        val degraded: ChoreOccurrenceDto = DefernoJson.decodeFromString(dropped)
        assertEquals(DerivedChoreOccurrenceStatusWire.Unknown, degraded.status)
        assertEquals(OccurrenceResolution.Scheduled, degraded.toFact(ItemKind.Chore, "chore-head-1")?.resolution)
    }

    @Test
    fun anUnresolvedChoreRowHasANullCompletedAt() {
        val json = """
            {
              "scheduled_date":"2026-05-17","status":"in_progress",
              "complete_by":"2026-05-17T21:00:00Z","completed_at":null,"segment_id":"s"
            }
        """.trimIndent()
        val dto: ChoreOccurrenceDto = DefernoJson.decodeFromString(json)
        val fact = dto.toFact(ItemKind.Chore, "chore-head-1")!!

        assertEquals(OccurrenceResolution.InProgress, fact.resolution)
        assertNull(fact.doneAt)
    }

    // ── event ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun eventOccurrenceDecodesTheFullSixteenFieldStructIncludingEmptyCommentAndAttachments() {
        // The unified `Occurrence`. `comment` is SINGULAR and is an array; it and `attachments` are
        // #[serde(default)] WITHOUT skip_serializing_if, so they arrive present-but-empty rather than
        // absent — decoding must not throw on either.
        val json = """
            {
              "id": "occ-1",
              "parent_id": "evt-seg-9",
              "scheduled_date": "2026-05-17",
              "complete_by": "2026-05-17T21:00:00Z",
              "status": "dropped",
              "done_at": "2026-05-17T20:00:00Z",
              "comment": [],
              "attachments": [],
              "title_override": "Standup (moved)",
              "description_override": "one-off agenda",
              "labels_override": ["work"],
              "assignee_override": "user-7",
              "desire_override": 0.25,
              "productive_override": 0.75,
              "rescheduled_to": "2026-05-18",
              "rescheduled_from": "2026-05-16"
            }
        """.trimIndent()
        val dto: EventOccurrenceDto = DefernoJson.decodeFromString(json)

        assertEquals("occ-1", dto.id)
        assertEquals("evt-seg-9", dto.parentId)
        assertEquals(OccurrenceStatusWire.Dropped, dto.status)
        assertTrue(dto.comment.isEmpty())
        assertTrue(dto.attachments.isEmpty())
        assertEquals("Standup (moved)", dto.titleOverride)
        assertEquals("one-off agenda", dto.descriptionOverride)
        assertEquals(listOf("work"), dto.labelsOverride)
        assertEquals("user-7", dto.assigneeOverride)
        assertEquals(0.25, dto.desireOverride)
        assertEquals(0.75, dto.productiveOverride)
        assertEquals("2026-05-18", dto.rescheduledTo)
        assertEquals("2026-05-16", dto.rescheduledFrom)

        val fact = dto.toFact(ItemKind.Event, definitionId = "evt-head-9")

        assertEquals(ItemKind.Event, fact.kind)
        assertEquals("evt-head-9", fact.definitionId)
        assertEquals(LocalDate(2026, 5, 17), fact.date)
        // The event terminal is spelled `dropped`; it condenses to the one domain Skipped.
        assertEquals(OccurrenceResolution.Skipped, fact.resolution)
        assertEquals(Instant.parse("2026-05-17T20:00:00Z"), fact.doneAt)
        assertEquals(Instant.parse("2026-05-17T21:00:00Z"), fact.completeBy)
    }

    @Test
    fun theEventEndpointHasNoSkippedVariantAndAScheduledRowIsStillAFact() {
        // `skipped` is not an event token: it coerces to Unknown, which degrades to Scheduled.
        val minimal = """
            {
              "id":"occ-2","parent_id":"evt-seg-9","scheduled_date":"2026-05-17",
              "complete_by":"2026-05-17T21:00:00Z","status":"skipped","comment":[],"attachments":[]
            }
        """.trimIndent()
        val degraded: EventOccurrenceDto = DefernoJson.decodeFromString(minimal)
        assertEquals(OccurrenceStatusWire.Unknown, degraded.status)
        assertEquals(OccurrenceResolution.Scheduled, degraded.toFact(ItemKind.Event, "evt-head-9").resolution)

        // A `scheduled` event row is a genuinely STORED row recording no progress — this endpoint
        // returns only stored rows (event_occurrences.rs:57-60) — so unlike the chore's derived
        // `scheduled` it is a fact, and the mapper is total.
        val scheduled: EventOccurrenceDto = DefernoJson.decodeFromString(minimal.replace("\"skipped\"", "\"scheduled\""))
        val fact = scheduled.toFact(ItemKind.Event, "evt-head-9")
        assertEquals(OccurrenceResolution.Scheduled, fact.resolution)
        assertNull(fact.doneAt)
    }
}
