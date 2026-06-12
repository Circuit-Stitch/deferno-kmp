package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit coverage for the **New** create surface's payload building (#71, ADR-0016) — the pure,
 * Compose-free [NewState.toPayload] mapping + its [NewState.canSubmit] gate. The Event arm is the
 * regression target (FIX 1, AC #2): a bare Event create must carry a **non-empty start** (`complete_by`)
 * and must **not** carry empty-string optional fields (the server rejects `"complete_by":""`, and
 * `explicitNulls=false` omits nulls but NOT empty strings). The companion serialization assertion — that
 * the *wire body* drops the empty optionals — lives in `core:network`'s `CreatePayloadSerializationTest`
 * (where `DefernoJson` is first-class); here we assert the payload the mapping actually emits.
 */
class NewComponentTest {

    private val start = Instant.parse("2026-06-08T09:00:00Z")
    private val end = Instant.parse("2026-06-08T10:00:00Z")

    @Test
    fun eventPayloadCarriesANonEmptyStart() {
        val payload = NewState(selectedKind = ItemKind.Event, title = "standup", start = start).toPayload()

        assertTrue(payload is CreateItem.Payload.Event, "Event maps to an Event payload")
        val event = payload.payload // smart-cast via the assertTrue above
        assertTrue(event.completeBy.isNotBlank(), "the Event start (complete_by) is non-empty")
        assertEquals(start.toString(), event.completeBy)
    }

    @Test
    fun bareEventCreateHasNoEmptyStringOptionalFields() {
        // No notes, no end: the optional fields must be NULL (omitted by explicitNulls=false), never "".
        val event = (NewState(selectedKind = ItemKind.Event, title = "standup", start = start)
            .toPayload() as CreateItem.Payload.Event).payload

        assertNull(event.description, "blank notes must be null, not \"\"")
        assertNull(event.endTime, "an absent end must be null, not \"\"")
        assertFalse(event.completeBy.isEmpty(), "the start is never an empty string")
    }

    @Test
    fun eventPayloadDerivesStartAndEndTimeOfDayFromTheInstants() {
        // #348: an Event must stay timed (not all-day), so the chosen start/end clock rides as
        // start_/end_time_of_day. In UTC the instants' clock is the local clock.
        val event = (NewState(selectedKind = ItemKind.Event, title = "standup", start = start, end = end)
            .toPayload(tz = "UTC") as CreateItem.Payload.Event).payload
        assertEquals("09:00", event.startTimeOfDay)
        assertEquals("10:00", event.endTimeOfDay)

        val dateOnly = (NewState(selectedKind = ItemKind.Event, title = "standup", date = LocalDate(2026, 6, 20))
            .toPayload(tz = "UTC") as CreateItem.Payload.Event).payload
        assertNull(dateOnly.startTimeOfDay, "a date-only Event has no clock ⇒ server derives all-day")
    }

    @Test
    fun taskPayloadCarriesDeadlineTimeOfDayOnlyWithADate() {
        val timed = (NewState(
            selectedKind = ItemKind.Task,
            title = "call",
            date = LocalDate(2026, 6, 20),
            deadlineTime = LocalTime(14, 30),
        ).toPayload(tz = "UTC") as CreateItem.Payload.Task).payload
        assertEquals("14:30", timed.deadlineTimeOfDay)

        // A time with no date is meaningless — it must not be sent.
        val noDate = (NewState(selectedKind = ItemKind.Task, title = "call", deadlineTime = LocalTime(14, 30))
            .toPayload(tz = "UTC") as CreateItem.Payload.Task).payload
        assertNull(noDate.deadlineTimeOfDay, "no date ⇒ no deadline_time_of_day")
    }

    @Test
    fun eventPayloadCarriesTheEndWhenSupplied_andOmitsItWhenNot() {
        val withEnd = (NewState(selectedKind = ItemKind.Event, title = "standup", start = start, end = end)
            .toPayload() as CreateItem.Payload.Event).payload
        assertEquals(end.toString(), withEnd.endTime)

        val noEnd = (NewState(selectedKind = ItemKind.Event, title = "standup", start = start)
            .toPayload() as CreateItem.Payload.Event).payload
        assertNull(noEnd.endTime, "no end when not supplied")
    }

    @Test
    fun anEventWithoutAStartCannotBeSubmitted() {
        // An Event needs a fixed start (AC #2). Title alone is not enough for the Event kind.
        assertFalse(
            NewState(selectedKind = ItemKind.Event, title = "standup", start = null).canSubmit,
            "an Event with no start is not submittable",
        )
        assertTrue(
            NewState(selectedKind = ItemKind.Event, title = "standup", start = start).canSubmit,
            "an Event with a start is submittable",
        )
    }

    @Test
    fun nonEventKindsStillSubmitWithJustATitle() {
        // The start requirement is Event-specific; the other kinds only need a non-blank title.
        assertTrue(NewState(selectedKind = ItemKind.Task, title = "buy milk").canSubmit)
        assertTrue(NewState(selectedKind = ItemKind.Habit, title = "stretch").canSubmit)
        assertTrue(NewState(selectedKind = ItemKind.Chore, title = "trash").canSubmit)

        val task = (NewState(selectedKind = ItemKind.Task, title = "buy milk").toPayload()
            as CreateItem.Payload.Task).payload
        assertEquals("buy milk", task.title)
        assertNull(task.description, "blank notes never become \"\" on a Task either")
    }
}
