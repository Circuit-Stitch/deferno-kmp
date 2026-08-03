package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The occurrence write intents (#74) — the firing-level sibling of `MutationTest`. It pins the
 * per-kind endpoint + minimal body the outbox replays (the habit-binary / chore-PUT / event-action
 * asymmetry), the [OutboxMethod] each uses (incl. the new `PUT` for a chore set-status), and the pure,
 * idempotent optimistic [applyTo] on the cached [CalendarItem] (the no-`missed` WorkingState axis).
 *
 * **Every fixture keeps the item id and the series id visibly distinct** (`hab-3-item` vs `hab-3-series`)
 * because that is the whole of #380: the endpoints resolve `{id}` through `load_item_for_user`, so the
 * *item* id belongs in the path slot. Passing the series id 404s, and the sender maps 404 to success —
 * a test that used one id for both could never see the difference.
 */
class OccurrenceMutationTest {

    private val date = LocalDate(2026, 6, 8)

    private fun item(status: WorkingState = WorkingState.Open, date: LocalDate = this.date) = CalendarItem(
        id = "ce-1",
        taskId = "hab-3-item",
        seriesId = "hab-3-series",
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
        val request = MarkOccurrence("ce-1", ItemKind.Habit, "hab-3-item", date, OccurrenceAction.Complete).toRequest()
        assertEquals(OutboxMethod.Post, request.method)
        // The ITEM id, not the series id: `POST /habits/{id}/occurrences` → `load_owned_habit`.
        assertEquals(listOf("habits", "hab-3-item", "occurrences"), request.path)
        assertEquals("""{"done":true,"date":"2026-06-08"}""", request.body)
        assertTrue(request.acceptsActivityStamp)

        // A non-Complete action on a habit clears done (the UI only offers Complete, but the body is total).
        assertEquals(
            """{"done":false,"date":"2026-06-08"}""",
            MarkOccurrence("ce-1", ItemKind.Habit, "hab-3-item", date, OccurrenceAction.Start).toRequest().body,
        )
    }

    @Test
    fun choreMarkIsAPutWithTheKindAppropriateStatusToken() {
        val start = MarkOccurrence("ce-1", ItemKind.Chore, "cho-1-item", date, OccurrenceAction.Start).toRequest()
        assertEquals(OutboxMethod.Put, start.method)
        assertEquals(listOf("chores", "cho-1-item", "occurrences", "2026-06-08"), start.path)
        assertEquals("""{"status":"in_progress"}""", start.body)
        assertTrue(start.acceptsActivityStamp)

        // A chore skip is the `skipped` token (not `dropped`).
        assertEquals(
            """{"status":"skipped"}""",
            MarkOccurrence("ce-1", ItemKind.Chore, "cho-1-item", date, OccurrenceAction.Skip).toRequest().body,
        )
    }

    @Test
    fun eventMarkPostsTheActionToken_skipIsDropped() {
        val complete = MarkOccurrence("ce-1", ItemKind.Event, "evt-1-item", date, OccurrenceAction.Complete).toRequest()
        assertEquals(OutboxMethod.Post, complete.method)
        assertEquals(listOf("events", "evt-1-item", "occurrences", "2026-06-08"), complete.path)
        assertEquals("""{"action":"done"}""", complete.body)
        assertTrue(complete.acceptsActivityStamp)

        // An event skip diverges from a chore: the wire token is `dropped`.
        assertEquals(
            """{"action":"dropped"}""",
            MarkOccurrence("ce-1", ItemKind.Event, "evt-1-item", date, OccurrenceAction.Skip).toRequest().body,
        )
    }

    @Test
    fun clearIsAPostSoftDeleteOnTheKindScopedClearSubresource() {
        // #364: the backend retired the bodiless `DELETE …/occurrences/{date}` in favour of a POST
        // soft-delete so Activity-ledger metadata can ride in a body, and left NO alias — the old route
        // 404s. The body is an empty object, not null: a null body sends no HTTP entity at all, leaving
        // the stamping decorator nothing to merge `activity` into.
        val request = ClearOccurrence("ce-1", ItemKind.Chore, "cho-1-item", date).toRequest()
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("chores", "cho-1-item", "occurrences", "2026-06-08", "clear"), request.path)
        assertEquals("{}", request.body)

        // All three kinds carry the stamp. Event-clear declares its body `oneOf [null, ActivityBody]`
        // rather than a bare `$ref`, so a contract scan that skips `oneOf` arms wrongly reads it as
        // body-less — the reason CONTRACT-NOTES pins the ingest surface at 36 routes, not 35.
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            assertTrue(
                ClearOccurrence("ce-1", kind, "i-1", date).toRequest().acceptsActivityStamp,
                "clear for $kind must declare the activity stamp",
            )
        }
    }

    @Test
    fun reschedulePostsTheNewDateToTheRescheduleSubresource() {
        val request = RescheduleOccurrence("ce-1", ItemKind.Event, "evt-1-item", date, LocalDate(2026, 6, 10)).toRequest()
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("events", "evt-1-item", "occurrences", "2026-06-08", "reschedule"), request.path)
        assertEquals("""{"new_date":"2026-06-10"}""", request.body)

        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            assertTrue(
                RescheduleOccurrence("ce-1", kind, "i-1", date, LocalDate(2026, 6, 10)).toRequest().acceptsActivityStamp,
                "reschedule for $kind must declare the activity stamp",
            )
        }
    }

    @Test
    fun rescheduleIsOfferedForAllThreeRecurringKinds() {
        // #380 defect 3: `reschedule_recurring_occurrence` is shared by all three kinds server-side, so
        // the intent must build the kind-scoped path for a habit and a chore too — not just an event.
        assertEquals(
            listOf("habits", "hab-3-item", "occurrences", "2026-06-08", "reschedule"),
            RescheduleOccurrence("ce-1", ItemKind.Habit, "hab-3-item", date, LocalDate(2026, 6, 10)).toRequest().path,
        )
        assertEquals(
            listOf("chores", "cho-1-item", "occurrences", "2026-06-08", "reschedule"),
            RescheduleOccurrence("ce-1", ItemKind.Chore, "cho-1-item", date, LocalDate(2026, 6, 10)).toRequest().path,
        )
    }

    @Test
    fun optimisticApplyIsPureAndIdempotent() {
        val mark = MarkOccurrence("ce-1", ItemKind.Event, "evt-1-item", date, OccurrenceAction.Complete)
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
        val fixture = item()
        assertEquals(
            "occurrence:Event:evt-1-item:2026-06-08",
            MarkOccurrence("ce-1", ItemKind.Event, "evt-1-item", date, OccurrenceAction.Complete).target,
        )
        // The target carries the item id the endpoints key on — never the series id (#380).
        assertNotEquals(fixture.taskId, fixture.seriesId)
        assertEquals(
            "occurrence:Habit:${fixture.taskId}:2026-06-08",
            MarkOccurrence("ce-1", ItemKind.Habit, fixture.taskId, date, OccurrenceAction.Complete).target,
        )
    }

    @Test
    fun everyIntentForOneFiringSharesTheSameTarget() {
        // The target is the firing *identity*, not the verb: a mark, a clear and a reschedule of the
        // same firing are the same target. That is the grouping key the flush-time coalescer keys on
        // (#396) and the reason replay is last-write-wins safe.
        val mark = MarkOccurrence("ce-1", ItemKind.Chore, "cho-1-item", date, OccurrenceAction.Complete).target
        val clear = ClearOccurrence("ce-1", ItemKind.Chore, "cho-1-item", date).target
        val reschedule = RescheduleOccurrence("ce-1", ItemKind.Chore, "cho-1-item", date, LocalDate(2026, 6, 10)).target
        assertEquals(mark, clear)
        assertEquals(mark, reschedule)
    }

    @Test
    fun aRescheduleKeysOnItsOriginDateNotItsDestination() {
        // The fact the #396 barrier rule rests on. A reschedule is an absolute write over TWO days, and
        // its target names the ORIGIN — so the destination day is a different key by construction, and a
        // later mark on the new day can never be collapsed into the write that moved the firing there.
        val move = RescheduleOccurrence("ce-1", ItemKind.Event, "evt-1-item", date, LocalDate(2026, 6, 10))
        assertEquals(OccurrenceTargets.of(ItemKind.Event, "evt-1-item", date), move.target)
        assertNotEquals(OccurrenceTargets.of(ItemKind.Event, "evt-1-item", move.newDate), move.target)
    }

    @Test
    fun theTargetRoundTripsThroughOccurrenceTargets() {
        val target = MarkOccurrence("ce-1", ItemKind.Chore, "cho-1-item", date, OccurrenceAction.Skip).target
        assertEquals(OccurrenceTarget(ItemKind.Chore, "cho-1-item", date), OccurrenceTargets.parse(target))
    }

    @Test
    fun aTaskIsRejectedByEveryKindScopedIntent() {
        // Task has no occurrence endpoints; each builder fails loudly rather than posting to `/tasks/…`.
        assertNull(runCatching { MarkOccurrence("ce-1", ItemKind.Task, "t-1", date, OccurrenceAction.Complete).toRequest() }.getOrNull())
        assertNull(runCatching { ClearOccurrence("ce-1", ItemKind.Task, "t-1", date).toRequest() }.getOrNull())
        assertNull(runCatching { RescheduleOccurrence("ce-1", ItemKind.Task, "t-1", date, date).toRequest() }.getOrNull())
    }
}
