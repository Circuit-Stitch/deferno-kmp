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
    fun anAllDayEventDropsBothClocksButKeepsItsWindow() {
        // #348: all-day is the ABSENCE of the two time-of-day fields — the server derives `all_day` from
        // that and rejects it as input, so the payload must carry neither clock and no `all_day` field
        // (there is none to set on CreateEventPayload). The day window itself survives: complete_by and
        // end_time still carry the chosen instants, so a multi-day all-day Event stays multi-day.
        val event = (NewState(selectedKind = ItemKind.Event, title = "conference", start = start, end = end, allDay = true)
            .toPayload(tz = "UTC") as CreateItem.Payload.Event).payload

        assertNull(event.startTimeOfDay, "an all-day Event sends no start clock")
        assertNull(event.endTimeOfDay, "an all-day Event sends no end clock")
        assertEquals(start.toString(), event.completeBy, "the start day still rides on complete_by")
        assertEquals(end.toString(), event.endTime, "the end day still rides on end_time")
    }

    @Test
    fun clearingAllDayRestoresTheClocksFromTheUntouchedInstants() {
        // The toggle only gates the derivation — it never edits start/end — so flipping it back gives the
        // person the clock they had picked, rather than silently resetting them to a default.
        val state = NewState(selectedKind = ItemKind.Event, title = "standup", start = start, end = end, allDay = true)
        val timed = (state.copy(allDay = false).toPayload(tz = "UTC") as CreateItem.Payload.Event).payload

        assertEquals("09:00", timed.startTimeOfDay)
        assertEquals("10:00", timed.endTimeOfDay)
    }

    @Test
    fun eventIsAllDayMatchesWhatThePayloadActuallySends() {
        // The reading a View renders must be the rule `toPayload` applies, or the form states a time the
        // POST won't carry. A pre-dated form (day, no start instant) has no clock to send — so it reads
        // all-day even though nobody flipped the switch.
        val preDated = NewState(selectedKind = ItemKind.Event, title = "standup", date = LocalDate(2026, 6, 20))
        assertTrue(preDated.eventIsAllDay, "no start instant ⇒ no clock ⇒ all-day")
        assertNull(
            (preDated.toPayload(tz = "UTC") as CreateItem.Payload.Event).payload.startTimeOfDay,
            "and the payload agrees",
        )

        val timed = NewState(selectedKind = ItemKind.Event, title = "standup", start = start)
        assertFalse(timed.eventIsAllDay, "an explicit start carries a clock")
        assertEquals(
            "09:00",
            (timed.toPayload(tz = "UTC") as CreateItem.Payload.Event).payload.startTimeOfDay,
            "and the payload agrees",
        )

        assertTrue(timed.copy(allDay = true).eventIsAllDay, "the explicit choice still wins")
        assertFalse(
            NewState(selectedKind = ItemKind.Task, title = "call", allDay = true).eventIsAllDay,
            "the reading is Event-only",
        )
    }

    @Test
    fun allDayIsIgnoredByTheNonEventKinds() {
        // A Task/Habit/Chore's all-day is the absence of `deadlineTime`; the Event-only flag must not
        // leak into their payloads (there is no clock to gate there).
        val task = (NewState(
            selectedKind = ItemKind.Task,
            title = "call",
            date = LocalDate(2026, 6, 20),
            deadlineTime = LocalTime(14, 30),
            allDay = true,
        ).toPayload(tz = "UTC") as CreateItem.Payload.Task).payload

        assertEquals("14:30", task.deadlineTimeOfDay, "allDay is Event-only — it must not drop a Task's clock")
    }

    @Test
    fun aPreDatedEventStaysAllDayWhenItsDayMoves() {
        // The Calendar-FAB shape: a day, no start instant. The Apple pickers seed the Starts row from that
        // day and write back through `setStart`, so the flag must be pinned first or the reading collapses
        // and the Event silently becomes timed at the seed's own midnight. Pinning is the bridges' job
        // (`pinAllDay`); this asserts the payload contract that makes it necessary and sufficient.
        val preDated = NewState(selectedKind = ItemKind.Event, title = "conference", date = LocalDate(2026, 6, 20))
        assertTrue(preDated.eventIsAllDay)

        // Unpinned — what a naive `setStart` would produce: the reading flips and a 00:00 clock ships.
        val unpinned = preDated.copy(start = Instant.parse("2026-06-25T00:00:00Z"))
        assertFalse(unpinned.eventIsAllDay, "the reading collapses to the raw flag once a start exists")
        assertEquals(
            "00:00",
            (unpinned.toPayload(tz = "UTC") as CreateItem.Payload.Event).payload.startTimeOfDay,
            "which is exactly the midnight clock the person never chose",
        )

        // Pinned — what the bridges actually do: the day moves, the Event stays all-day.
        val pinned = unpinned.copy(allDay = true)
        assertTrue(pinned.eventIsAllDay)
        val payload = (pinned.toPayload(tz = "UTC") as CreateItem.Payload.Event).payload
        assertNull(payload.startTimeOfDay, "no clock ships")
        assertEquals("2026-06-25T00:00:00Z", payload.completeBy, "and the moved day is what lands")
    }

    @Test
    fun aPreDatedEventWithNoStartIsStillSubmittable() {
        // `canSubmit`'s Event arm accepts a pre-dated day as the start, and `eventEndBeforeStart` must not
        // block it: with `start == null` there is no window to invert, whatever the end says.
        val preDated = NewState(
            selectedKind = ItemKind.Event,
            title = "conference",
            date = LocalDate(2026, 6, 20),
            end = end,
        )
        assertFalse(preDated.eventEndBeforeStart, "no start instant ⇒ no window to invert")
        assertTrue(preDated.canSubmit)
        assertEquals(
            LocalDate(2026, 6, 20).toString(),
            (preDated.toPayload(tz = "UTC") as CreateItem.Payload.Event).payload.completeBy.substringBefore('T'),
            "and the pre-dated day is what becomes complete_by",
        )
    }

    @Test
    fun anEventWhoseEndPrecedesItsStartCannotBeSubmitted() {
        // `end_time` must be >= complete_by; blocking here beats POSTing a guaranteed rejection.
        val inverted = NewState(selectedKind = ItemKind.Event, title = "standup", start = end, end = start)
        assertTrue(inverted.eventEndBeforeStart, "an inverted window is flagged")
        assertFalse(inverted.canSubmit, "an inverted window is not submittable")

        // An open-ended Event is valid — a null end is not an inverted one.
        val openEnded = NewState(selectedKind = ItemKind.Event, title = "standup", start = start, end = null)
        assertFalse(openEnded.eventEndBeforeStart)
        assertTrue(openEnded.canSubmit)

        // Same instant on both edges is a zero-length window, not an inverted one.
        assertTrue(NewState(selectedKind = ItemKind.Event, title = "standup", start = start, end = start).canSubmit)
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
