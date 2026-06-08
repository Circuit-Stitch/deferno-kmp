package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The occurrence write intents (#74) — the firing-level sibling of `MutationTest`. It pins the
 * per-kind endpoint + minimal body the outbox replays (the habit-binary / chore-PUT / event-action
 * asymmetry), the [OutboxMethod] each uses (incl. the new `PUT` for a chore set-status), and the pure,
 * idempotent optimistic [applyTo] on the cached [CalendarItem] (the no-`missed` WorkingState axis).
 */
class OccurrenceMutationTest {

    private val date = LocalDate(2026, 6, 8)

    private fun item(status: WorkingState = WorkingState.Open, date: LocalDate = this.date) = CalendarItem(
        id = "ce-1",
        taskId = "task-1",
        seriesId = "series-1",
        title = "Morning stretch",
        date = date,
        start = Instant.parse("2026-06-08T09:00:00Z"),
        end = Instant.parse("2026-06-08T09:15:00Z"),
        allDay = false,
        status = status,
        kind = ItemKind.Habit,
        source = CalendarSource.Deferno,
    )

    @Test
    fun habitMarkIsBinaryDoneWithTheDateInBody() {
        val request = MarkOccurrence("ce-1", ItemKind.Habit, "hab-3", date, OccurrenceAction.Complete).toRequest()
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("habits", "hab-3", "occurrences"), request.path)
        assertEquals("""{"done":true,"date":"2026-06-08"}""", request.body)

        // A non-Complete action on a habit clears done (the UI only offers Complete, but the body is total).
        assertEquals(
            """{"done":false,"date":"2026-06-08"}""",
            MarkOccurrence("ce-1", ItemKind.Habit, "hab-3", date, OccurrenceAction.Start).toRequest().body,
        )
    }

    @Test
    fun choreMarkIsAPutWithTheKindAppropriateStatusToken() {
        val start = MarkOccurrence("ce-1", ItemKind.Chore, "cho-1", date, OccurrenceAction.Start).toRequest()
        assertEquals(OutboxMethod.Put, start.method)
        assertEquals(listOf("chores", "cho-1", "occurrences", "2026-06-08"), start.path)
        assertEquals("""{"status":"in_progress"}""", start.body)

        // A chore skip is the `skipped` token (not `dropped`).
        assertEquals(
            """{"status":"skipped"}""",
            MarkOccurrence("ce-1", ItemKind.Chore, "cho-1", date, OccurrenceAction.Skip).toRequest().body,
        )
    }

    @Test
    fun eventMarkPostsTheActionToken_skipIsDropped() {
        val complete = MarkOccurrence("ce-1", ItemKind.Event, "evt-1", date, OccurrenceAction.Complete).toRequest()
        assertEquals(OutboxMethod.Post, complete.method)
        assertEquals(listOf("events", "evt-1", "occurrences", "2026-06-08"), complete.path)
        assertEquals("""{"action":"done"}""", complete.body)

        // An event skip diverges from a chore: the wire token is `dropped`.
        assertEquals(
            """{"action":"dropped"}""",
            MarkOccurrence("ce-1", ItemKind.Event, "evt-1", date, OccurrenceAction.Skip).toRequest().body,
        )
    }

    @Test
    fun clearIsABodilessDeleteOnTheKindScopedPath() {
        val request = ClearOccurrence("ce-1", ItemKind.Chore, "cho-1", date).toRequest()
        assertEquals(OutboxMethod.Delete, request.method)
        assertEquals(listOf("chores", "cho-1", "occurrences", "2026-06-08"), request.path)
        assertNull(request.body)
    }

    @Test
    fun reschedulePostsTheNewDateToTheRescheduleSubresource() {
        val request = RescheduleOccurrence("ce-1", ItemKind.Event, "evt-1", date, LocalDate(2026, 6, 10)).toRequest()
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("events", "evt-1", "occurrences", "2026-06-08", "reschedule"), request.path)
        assertEquals("""{"new_date":"2026-06-10"}""", request.body)
    }

    @Test
    fun optimisticApplyIsPureAndIdempotent() {
        val mark = MarkOccurrence("ce-1", ItemKind.Event, "evt-1", date, OccurrenceAction.Complete)
        val once = mark.applyTo(item())
        assertEquals(WorkingState.Done, once.status)
        // Replay-safe: applying twice is the same as once (mirrors the wire intent's idempotence).
        assertEquals(once, mark.applyTo(once))

        assertEquals(WorkingState.Dropped, MarkOccurrence("ce-1", ItemKind.Chore, "c", date, OccurrenceAction.Skip).applyTo(item()).status)
        assertEquals(WorkingState.Open, ClearOccurrence("ce-1", ItemKind.Habit, "h", date).applyTo(item(status = WorkingState.Done)).status)
        assertEquals(LocalDate(2026, 6, 10), RescheduleOccurrence("ce-1", ItemKind.Event, "e", date, LocalDate(2026, 6, 10)).applyTo(item()).date)
    }

    @Test
    fun targetEncodesTheFiringIdentity() {
        assertEquals(
            "occurrence:Event:evt-1:2026-06-08",
            MarkOccurrence("ce-1", ItemKind.Event, "evt-1", date, OccurrenceAction.Complete).target,
        )
    }
}
