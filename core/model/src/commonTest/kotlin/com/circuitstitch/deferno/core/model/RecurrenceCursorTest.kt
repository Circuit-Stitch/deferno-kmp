package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Contract for the **recurrence cursor** reading (#384) over the [Item] projection's
 * [Item.recurrence] + [Item.recurrenceCursorAt] pair.
 *
 * The load-bearing semantic under test comes from the backend's `2026-06-02-recurrence-anchor-and-bound`
 * ADR: `complete_by` on a recurring definition is a *moving cursor*, not an upper bound. So a **past**
 * cursor is a live series that has not advanced ([RecurrenceCursor.DueOn] pointing backwards), and a
 * **cleared** cursor is a series that ran out ([RecurrenceCursor.Exhausted]) — the two readings a bare
 * null-check would collapse into "no deadline". The rule is the discriminator, the cursor is the value.
 *
 * Every case pins [today] and the [TimeZone] explicitly: a test that reached for the real clock would
 * pass today and rot tomorrow, which is the exact bug the render-time contract exists to prevent.
 */
class RecurrenceCursorTest {

    private val today = LocalDate(2026, 6, 15)
    private val zone = TimeZone.UTC

    /** A recurring row as the projection builds it — the rule and the cursor supplied independently. */
    private fun item(
        recurrence: Recurrence?,
        completeBy: Instant?,
        kind: ItemKind = ItemKind.Habit,
        state: DefinitionState? = DefinitionState.Active,
    ) = Item(
        id = "i",
        kind = kind,
        title = "stretch",
        definitionState = state,
        recurrence = recurrence,
        recurrenceCursorAt = completeBy,
    )

    private fun cursorOf(
        recurrence: Recurrence?,
        completeBy: Instant?,
        kind: ItemKind = ItemKind.Habit,
        state: DefinitionState? = DefinitionState.Active,
    ) = item(recurrence, completeBy, kind, state).recurrenceCursor(zone, today)

    private val daily = Recurrence(Cadence.Daily)

    @Test
    fun aTaskWithNeitherRuleNorCursorHasNoCursorReading() {
        // The Task arm of the projection carries neither field — there is no series to say anything about.
        assertEquals(RecurrenceCursor.NoCursor, cursorOf(null, null, ItemKind.Task))
    }

    @Test
    fun aCursorWithoutARuleIsStillNoCursor() {
        // The RULE is the discriminator. A dated row with no recurrence is not a series, however the
        // cursor field happens to be populated — this must never read as a due-or-exhausted series.
        assertEquals(RecurrenceCursor.NoCursor, cursorOf(null, Instant.parse("2026-06-16T09:00:00Z")))
    }

    @Test
    fun aRuleWithNoCursorIsExhausted() {
        // The server clears `complete_by` when the bound is reached (`ScheduleAdvance::Ended`), so a rule
        // whose cursor is gone means the series ran out — NOT "no deadline set".
        assertEquals(RecurrenceCursor.Exhausted, cursorOf(Recurrence(Cadence.Daily, RecurrenceBound.AfterCount(4)), null))
    }

    @Test
    fun aRuleWithABoundStillReadsItsLiveCursorRatherThanTheBound() {
        // The bound lives on the rule (UNTIL/COUNT); the cursor is where the series has walked to. A
        // bounded, still-running series reads from the cursor — the bound never overrides it.
        val bounded = Recurrence(Cadence.EveryNDays(3), RecurrenceBound.OnDate(LocalDate(2026, 7, 1)))
        assertEquals(RecurrenceCursor.DueOn(RelativeDay.Tomorrow), cursorOf(bounded, Instant.parse("2026-06-16T09:00:00Z")))
    }

    @Test
    fun cursorOnTodayIsDueToday() {
        assertEquals(RecurrenceCursor.DueOn(RelativeDay.Today), cursorOf(daily, Instant.parse("2026-06-15T23:00:00Z")))
    }

    @Test
    fun aPastCursorOnAHabitIsOverdueNotExhausted() {
        // The distinction the whole type exists for: a Habit's cursor only advances on mark-done, so
        // yesterday's cursor is a LIVE series that was missed — Exhausted here would be a lie.
        assertEquals(RecurrenceCursor.DueOn(RelativeDay.Yesterday), cursorOf(daily, Instant.parse("2026-06-14T09:00:00Z")))
    }

    @Test
    fun aFarFutureCursorReadsAsDaysAway() {
        assertEquals(RecurrenceCursor.DueOn(RelativeDay.DaysAway(71)), cursorOf(daily, Instant.parse("2026-08-25T09:00:00Z")))
    }

    @Test
    fun aFarPastCursorReadsAsDaysAgo() {
        // #277 in the live account: overdue since 26 Jul 2025, and still a live Habit.
        assertEquals(RecurrenceCursor.DueOn(RelativeDay.DaysAgo(324)), cursorOf(daily, Instant.parse("2025-07-26T09:00:00Z")))
    }

    @Test
    fun theZoneChoosesTheDayTheCursorLandsOn() {
        // Same instant, two zones, two readings — which is why the zone is a parameter rather than a
        // hardcoded `currentSystemDefault()` inside the function (#392 tracks account-vs-device zone).
        val instant = Instant.parse("2026-06-15T23:00:00Z")
        val item = item(daily, instant)
        assertEquals(RecurrenceCursor.DueOn(RelativeDay.Today), item.recurrenceCursor(TimeZone.UTC, today))
        assertEquals(RecurrenceCursor.DueOn(RelativeDay.Tomorrow), item.recurrenceCursor(TimeZone.of("UTC+2"), today))
    }

    @Test
    fun theReadingIsTheSameForEveryRecurringKind() {
        // Kind is not a term in the reading — Habit/Chore/Event all read their pair identically (the
        // cross-kind Item projection is the point: one forest, one reading).
        val cursor = Instant.parse("2026-06-17T09:00:00Z")
        val expected = RecurrenceCursor.DueOn(RelativeDay.DaysAway(2))
        assertEquals(expected, cursorOf(daily, cursor, ItemKind.Habit))
        assertEquals(expected, cursorOf(daily, cursor, ItemKind.Chore))
        assertEquals(expected, cursorOf(daily, cursor, ItemKind.Event))
    }

    @Test
    fun theDefaultedTodayAndZoneResolveWithoutArguments() {
        // The no-arg form reads against the device clock/zone: assert only what cannot rot — that a rule
        // with no cursor is Exhausted whatever "today" is, and that a live cursor produces some DueOn.
        assertEquals(RecurrenceCursor.Exhausted, item(daily, null).recurrenceCursor())
        assertEquals(RecurrenceCursor.NoCursor, item(null, null).recurrenceCursor())
        val live = item(daily, Instant.parse("2026-06-15T12:00:00Z")).recurrenceCursor()
        assertTrue(live is RecurrenceCursor.DueOn, "a live cursor must read as DueOn whatever today is, was $live")
    }

    @Test
    fun anArchivedDefinitionHasNoNextEvenThoughTheServerKeepsItsCursor() {
        // `archive_habit` explicitly "doesn't touch complete_by/series_id" — it vacates the due/series
        // indexes instead — so a switched-off definition keeps a stale cursor forever. Reading it would
        // tell the user that something they archived months ago is overdue.
        val stale = Instant.parse("2026-01-05T09:00:00Z") // long past `today`
        assertEquals(RecurrenceCursor.NoCursor, cursorOf(daily, stale, state = DefinitionState.Archived))
        // The same row while Active is the overdue reading — the gate is the light switch, nothing else.
        assertEquals(
            RecurrenceCursor.DueOn(RelativeDay.DaysAgo(161)),
            cursorOf(daily, stale, state = DefinitionState.Active),
        )
    }

    @Test
    fun anArchivedDefinitionWithAnExhaustedSeriesAlsoReadsNoCursor() {
        // Archived wins over Exhausted: both say "no next", and NoCursor is the weaker claim of the two.
        assertEquals(RecurrenceCursor.NoCursor, cursorOf(daily, null, state = DefinitionState.Archived))
    }

    @Test
    fun supplyingOnlyAZoneResolvesTodayInThatSameZone() {
        // The regression this guards: `today` used to default to the DEVICE zone regardless of `zone`, so a
        // caller passing only an account zone (#392's shape) compared a day resolved in one zone against
        // "today" in another — a silent one-day slip.
        //
        // `now` is the discriminating cursor: whatever the zone, "now" is always *today* in that same zone,
        // so a correctly coupled default reads Today for every zone on every machine. Were the two
        // decoupled, Kiritimati (UTC+14) and Niue (UTC-11) are 25 hours apart, so at least one of them
        // disagrees with the device's day for almost every instant and this reads DaysAway/DaysAgo(1).
        val now = Clock.System.now()
        listOf(TimeZone.of("Pacific/Kiritimati"), TimeZone.UTC, TimeZone.of("Pacific/Niue")).forEach { z ->
            assertEquals(
                RecurrenceCursor.DueOn(RelativeDay.Today),
                item(daily, now).recurrenceCursor(zone = z),
                "`now` must read as Today in $z — `today` has to derive from the supplied zone",
            )
        }
    }
}
