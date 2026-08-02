package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.domain.command.CommandKind
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the **New** create surface's payload building (#71, ADR-0016) — the pure,
 * Compose-free [NewState.toPayload] mapping + its [NewState.canSubmit] gate.
 *
 * The Event arm is the regression target. Its WHEN is **two axes of (calendar day, optional clock)**
 * — never a fused instant — mirroring the server's own decomposition (Deferno ADR 2026-06-10; see
 * `docs/adr/0048`). Two properties are load-bearing and asserted throughout:
 *
 * 1. **The instants this client sends are already normalized.** `backend/src/time.rs`
 *    `normalize_when_instant` takes only the *local date* of a submitted instant and re-attaches the
 *    explicit `*_time_of_day`, or the inclusive end-of-day sentinel `23:59:59` when that is null.
 *    Because create is **offline-first** (#185) the optimistically-inserted row is whatever we put in
 *    the payload, so sending anything else would make the local row disagree with the server's until
 *    the outbox drains. Re-normalizing a normalized instant is documented as a no-op.
 * 2. **[NewState.eventEndBeforeStart] predicts the server's verdict without asking it.** Offline there
 *    is no 400 to catch the mistake — an invalid Event is inserted, enqueued, and fails silently at
 *    sync — so the client guard must compare the *same* two normalized values `Event::validate`
 *    (`backend/src/models/event.rs:295`) compares.
 */
class NewComponentTest {

    private val day = LocalDate(2026, 6, 20)
    private val nextDay = LocalDate(2026, 6, 21)
    private val nine = LocalTime(9, 0)
    private val ten = LocalTime(10, 0)

    private fun event(
        title: String = "standup",
        date: LocalDate? = day,
        startTime: LocalTime? = null,
        endDate: LocalDate? = null,
        endTime: LocalTime? = null,
    ) = NewState(
        selectedKind = ItemKind.Event,
        title = title,
        date = date,
        startTime = startTime,
        endDate = endDate,
        endTime = endTime,
    )

    private fun NewState.eventPayload(tz: String = "UTC") =
        (toPayload(tz) as CreateItem.Payload.Event).payload

    // ── The instant contract: what we send is what the server will store ──────────────────────────

    @Test
    fun eventPayloadCarriesANonEmptyStartAtTheChosenClock() {
        val payload = event(startTime = nine).toPayload()

        assertTrue(payload is CreateItem.Payload.Event, "Event maps to an Event payload")
        val e = payload.payload // smart-cast via the assertTrue above
        assertTrue(e.completeBy.isNotBlank(), "the Event start (complete_by) is non-empty")
        assertEquals("2026-06-20T09:00:00Z", e.completeBy)
    }

    @Test
    fun anAllDayEdgeIsSentAtTheInclusiveEndOfDaySentinel() {
        // The single producer's rule (backend/src/time.rs:137): no explicit clock ⇒ the local date at
        // 23:59:59. Sending start-of-day instead would leave the offline row ~24h from the server's.
        val e = event(endDate = day).eventPayload()

        assertEquals("2026-06-20T23:59:59Z", e.completeBy, "an all-day start rides the EOD sentinel")
        assertEquals("2026-06-20T23:59:59Z", e.endTime, "and so does an all-day end")
    }

    @Test
    fun instantsResolveInTheAccountZoneNotUtc() {
        // Every other test runs tz = "UTC", where the conversion is the identity and a zone bug is
        // invisible. In New York the all-day sentinel's *UTC* date is the following day while its
        // local date — the only part the server reads back — is still the 20th. Conflating the two is
        // the documented off-by-one (CONTEXT.md → "Due date").
        val timed = event(startTime = nine).eventPayload(tz = "America/New_York")
        assertEquals("2026-06-20T13:00:00Z", timed.completeBy, "09:00 EDT is 13:00Z")

        val allDay = event().eventPayload(tz = "America/New_York")
        assertEquals("2026-06-21T03:59:59Z", allDay.completeBy, "23:59:59 EDT is 03:59:59Z the next day")

        val task = (NewState(selectedKind = ItemKind.Task, title = "call", date = day, deadlineTime = nine)
            .toPayload(tz = "America/New_York") as CreateItem.Payload.Task).payload
        assertEquals("2026-06-20T13:00:00Z", task.completeBy, "the deadline axis normalizes identically")
    }

    @Test
    fun bareEventCreateHasNoEmptyStringOptionalFields() {
        // No notes, no end: the optional fields must be NULL (omitted by explicitNulls=false), never "".
        val e = event(startTime = nine).eventPayload()

        assertNull(e.description, "blank notes must be null, not \"\"")
        assertNull(e.endTime, "an absent end must be null, not \"\"")
        assertFalse(e.completeBy.isEmpty(), "the start is never an empty string")
    }

    @Test
    fun eventPayloadCarriesTheEndWhenSupplied_andOmitsItWhenNot() {
        val withEnd = event(startTime = nine, endDate = day, endTime = ten).eventPayload()
        assertEquals("2026-06-20T10:00:00Z", withEnd.endTime)

        assertNull(event(startTime = nine).eventPayload().endTime, "no end when not supplied")
    }

    // ── All-day is the absence of a clock, on each axis independently ─────────────────────────────

    @Test
    fun eventIsAllDayIsExactlyTheServersDerivedRule() {
        // derive_all_day (backend/src/models/event.rs:262): all-day iff NEITHER axis carries a clock.
        assertTrue(event().eventIsAllDay, "no clock on either axis")
        assertTrue(event(endDate = day).eventIsAllDay, "an all-day end does not make it timed")
        assertFalse(event(startTime = nine).eventIsAllDay, "a start clock makes it timed")
        assertFalse(event(endDate = day, endTime = ten).eventIsAllDay, "so does an end clock alone")
        assertFalse(
            NewState(selectedKind = ItemKind.Task, title = "call", date = day).eventIsAllDay,
            "the reading is Event-only",
        )
    }

    @Test
    fun anAllDayEventWithAnEndSendsNeitherClock() {
        // THE Calendar-FAB shape: a pre-dated day, Ends → Add, Create. Under the fused-instant model
        // this POSTed `end_time_of_day: "00:00"` against a null start clock, so the server derived
        // all_day = false and then rejected the inverted normalized window. Both clocks must be absent.
        val e = event(title = "conference", endDate = day).eventPayload()

        assertNull(e.startTimeOfDay, "an all-day Event sends no start clock")
        assertNull(e.endTimeOfDay, "an all-day Event sends no end clock")
        assertEquals("2026-06-20T23:59:59Z", e.completeBy, "the start day still rides on complete_by")
        assertEquals("2026-06-20T23:59:59Z", e.endTime, "and the end day on end_time")
        assertTrue(event(title = "conference", endDate = day).canSubmit, "and it is submittable")
    }

    @Test
    fun theClocksRideAsTimeOfDayWhenPresent() {
        val e = event(startTime = nine, endDate = day, endTime = ten).eventPayload()
        assertEquals("09:00", e.startTimeOfDay)
        assertEquals("10:00", e.endTimeOfDay)
    }

    @Test
    fun theEventClocksAreIgnoredByTheNonEventKinds() {
        // A Task/Habit/Chore's all-day is the absence of `deadlineTime`; the Event axes must not leak.
        val task = (NewState(
            selectedKind = ItemKind.Task,
            title = "call",
            date = day,
            deadlineTime = LocalTime(14, 30),
            startTime = nine,
            endDate = nextDay,
            endTime = ten,
        ).toPayload(tz = "UTC") as CreateItem.Payload.Task).payload

        assertEquals("14:30", task.deadlineTimeOfDay, "the deadline clock is the one that ships")
        assertEquals("2026-06-20T14:30:00Z", task.completeBy, "on the deadline axis, not the Event's")
    }

    @Test
    fun taskPayloadCarriesDeadlineTimeOfDayOnlyWithADate() {
        val timed = (NewState(
            selectedKind = ItemKind.Task,
            title = "call",
            date = day,
            deadlineTime = LocalTime(14, 30),
        ).toPayload(tz = "UTC") as CreateItem.Payload.Task).payload
        assertEquals("14:30", timed.deadlineTimeOfDay)

        // A time with no date is meaningless — it must not be sent.
        val noDate = (NewState(selectedKind = ItemKind.Task, title = "call", deadlineTime = LocalTime(14, 30))
            .toPayload(tz = "UTC") as CreateItem.Payload.Task).payload
        assertNull(noDate.deadlineTimeOfDay, "no date ⇒ no deadline_time_of_day")
        assertNull(noDate.completeBy, "and no complete_by either")
    }

    // ── The window guard: it must agree with Event::validate, in BOTH directions ──────────────────

    @Test
    fun anEventWhoseEndPrecedesItsStartCannotBeSubmitted() {
        // `end_time` must be >= `complete_by` (backend/src/models/event.rs:300); blocking here beats
        // enqueuing a create that will fail at sync with nothing on screen to explain it.
        val inverted = event(startTime = ten, endDate = day, endTime = nine)
        assertTrue(inverted.eventEndBeforeStart, "an inverted window is flagged")
        assertFalse(inverted.canSubmit, "an inverted window is not submittable")

        val earlierDay = event(startTime = nine, endDate = LocalDate(2026, 6, 8), endTime = ten)
        assertTrue(earlierDay.eventEndBeforeStart, "an end day before the start day is inverted too")

        // An open-ended Event is valid — a null end is not an inverted one.
        val openEnded = event(startTime = nine)
        assertFalse(openEnded.eventEndBeforeStart)
        assertTrue(openEnded.canSubmit)

        // Same day and clock on both edges is a zero-length window, not an inverted one.
        assertTrue(event(startTime = nine, endDate = day, endTime = nine).canSubmit)
    }

    @Test
    fun anAllDayStartWithATimedEndOnTheSameDayIsRejected() {
        // The offline-first false-ACCEPT. Both edges are June 20, so a naive day-only comparison sees
        // nothing wrong — but the server normalizes the clockless start to 23:59:59 and the end to
        // 09:00, and 400s. Offline there is no 400: the create is optimistically inserted, enqueued,
        // and fails silently long after the person has moved on. The guard must catch it here.
        val state = event(startTime = null, endDate = day, endTime = nine)

        assertTrue(state.eventEndBeforeStart, "23:59:59 start vs 09:00 end on one day is inverted")
        assertFalse(state.canSubmit)
    }

    @Test
    fun aTimedStartWithAnAllDayEndOnTheSameDayIsAccepted() {
        // The mirror case — the one the fused-instant guard got wrong in the other direction. The
        // clockless end normalizes to 23:59:59, which is after a 09:00 start, so the server accepts
        // and the client must too rather than blocking a legal Event.
        val state = event(startTime = nine, endDate = day, endTime = null)

        assertFalse(state.eventEndBeforeStart, "09:00 start vs a 23:59:59 end is a valid window")
        assertTrue(state.canSubmit)
    }

    @Test
    fun anAllDayEventWithInvertedClocksOnTheSameDayIsAccepted() {
        // Both axes clockless ⇒ both normalize to the same 23:59:59 sentinel ⇒ equal, not inverted.
        // There is no clock left to invert, so the fact that "the end reads earlier" is not expressible.
        val state = event(endDate = day)

        assertFalse(state.eventEndBeforeStart)
        assertTrue(state.canSubmit)
    }

    // ── canSubmit ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anEventWithoutADayCannotBeSubmitted() {
        // An Event needs a start day (AC #2) — `complete_by` is required and non-empty on the wire.
        assertFalse(event(date = null).canSubmit, "an Event with no day is not submittable")
        assertFalse(
            event(date = null, startTime = nine).canSubmit,
            "a clock with no day is still not submittable — a time needs a day to live on",
        )
        assertTrue(event().canSubmit, "a bare pre-dated day is enough")
    }

    @Test
    fun nonEventKindsStillSubmitWithJustATitle() {
        // The day requirement is Event-specific; the other kinds only need a non-blank title.
        assertTrue(NewState(selectedKind = ItemKind.Task, title = "buy milk").canSubmit)
        assertTrue(NewState(selectedKind = ItemKind.Habit, title = "stretch").canSubmit)
        assertTrue(NewState(selectedKind = ItemKind.Chore, title = "trash").canSubmit)

        val task = (NewState(selectedKind = ItemKind.Task, title = "buy milk").toPayload()
            as CreateItem.Payload.Task).payload
        assertEquals("buy milk", task.title)
        assertNull(task.description, "blank notes never become \"\" on a Task either")
    }

    // ── The component's own invariants (a clock cannot outlive its day) ───────────────────────────

    @Test
    fun clearingADayAlsoClearsItsClock() {
        // `toPayload` drops a clock whose day is gone, so leaving it set would leave the form showing
        // a time that silently never ships. The invariant lives HERE, not in each platform's bridge —
        // that is what left Android and desktop broken while the Apple bridges enforced it twice.
        val component = newComponent()

        component.setDate(day)
        component.setDeadlineTime(nine)
        component.setDate(null)
        assertNull(component.state.value.deadlineTime, "clearing the date clears the deadline clock")

        component.setDate(day)
        component.setStartTime(nine)
        component.setDate(null)
        assertNull(component.state.value.startTime, "and the Event's start clock")

        component.setEndDate(nextDay)
        component.setEndTime(ten)
        component.setEndDate(null)
        assertNull(component.state.value.endTime, "clearing the end date clears the end clock")
    }

    private fun newComponent() = DefaultNewComponent(
        create = { CommandResult.Accepted(CommandKind.CreateItem) },
        onCreated = {},
        launch = {},
    )
}
