package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The domain contract for the widened [Recurrence] (#382). Before the widening the domain could express
 * only "a frequency and, if weekly, some days" — so `every_n_days` and `custom` had no representation at
 * all and collapsed to [RecurrenceFrequency.Unknown], while `monthly`/`yearly` kept their name but lost
 * every parameter that made the rule mean anything. Since the cached row is written *from* this type,
 * whatever the domain cannot say is destroyed by a cache round-trip, not merely unrendered.
 *
 * These pin the three properties the rest of the fix leans on: every parameter is defaulted (so the
 * widening is source-compatible and old call sites still read as they did), the two bound/anchor sealed
 * hierarchies are total and value-comparable, and [Recurrence.rawType] is the escape hatch that lets an
 * unmodellable cadence survive.
 */
class RecurrenceTest {

    @Test
    fun everyParameterIsDefaultedSoABareCadenceStaysConstructible() {
        // Source-compatibility is load-bearing: a required parameter here would have broken every
        // existing `Recurrence(frequency)` / `Recurrence(frequency, days)` construction in the tree.
        val daily = Recurrence(RecurrenceFrequency.Daily)

        assertEquals(emptyList(), daily.days)
        assertNull(daily.interval)
        assertNull(daily.monthlyAnchor)
        assertNull(daily.month)
        assertNull(daily.day)
        assertNull(daily.rrule)
        assertNull(daily.rawType)
        // The default bound is Never — an unbounded rule, which is what a rule with no `end` key means.
        assertEquals(RecurrenceBound.Never, daily.bound)
        assertEquals(Recurrence(RecurrenceFrequency.Weekly, listOf("Tue")), Recurrence(RecurrenceFrequency.Weekly, days = listOf("Tue")))
    }

    @Test
    fun theCycleMultiplierIsOneConceptSharedByEveryCadenceThatHasOne() {
        // "Every 3 days" and "every 2 months" are the same domain idea — a cycle multiplier whose UNIT
        // the frequency supplies. The wire spells them with two different keys (`n` and `interval`) that
        // can never co-occur, so condensing them here loses nothing and keeps the model honest.
        assertEquals(3, Recurrence(RecurrenceFrequency.EveryNDays, interval = 3).interval)
        assertEquals(2, Recurrence(RecurrenceFrequency.Monthly, interval = 2).interval)
        assertEquals(2, Recurrence(RecurrenceFrequency.Yearly, interval = 2, month = 6, day = 14).interval)
    }

    @Test
    fun allSixCadencesAreExpressibleAndDistinct() {
        // The backend's `Cadence` is a closed six-variant enum; `Unknown` is a seventh, client-only arm
        // for a cadence a future backend adds. Two rules that differ only in a parameter must not be
        // equal — the every-30-days vs every-29-days indistinguishability was the reported symptom.
        val rules = listOf(
            Recurrence(RecurrenceFrequency.Daily),
            Recurrence(RecurrenceFrequency.EveryNDays, interval = 29),
            Recurrence(RecurrenceFrequency.EveryNDays, interval = 30),
            Recurrence(RecurrenceFrequency.Weekly, days = listOf("Tue")),
            Recurrence(RecurrenceFrequency.Monthly, interval = 1, monthlyAnchor = MonthlyAnchor.DayOfMonth(15)),
            Recurrence(RecurrenceFrequency.Yearly, interval = 1, month = 6, day = 14),
            Recurrence(RecurrenceFrequency.Custom, rrule = "FREQ=WEEKLY;BYDAY=MO"),
        )

        assertEquals(rules.size, rules.toSet().size, "every cadence is distinguishable from the others")
        assertNotEquals(rules[1], rules[2], "every 29 days is not every 30 days")
    }

    @Test
    fun theBoundIsATotalSealedHierarchyOfThreeCases() {
        assertIs<RecurrenceBound>(RecurrenceBound.Never)
        assertEquals(RecurrenceBound.OnDate(LocalDate(2027, 1, 31)), RecurrenceBound.OnDate(LocalDate(2027, 1, 31)))
        assertEquals(RecurrenceBound.AfterCount(10), RecurrenceBound.AfterCount(10))
        assertNotEquals<RecurrenceBound>(RecurrenceBound.AfterCount(10), RecurrenceBound.AfterCount(11))
        // Never is a singleton object, so an unbounded rule compares equal across instances for free.
        assertEquals(
            Recurrence(RecurrenceFrequency.Daily),
            Recurrence(RecurrenceFrequency.Daily, bound = RecurrenceBound.Never),
        )

        // Exhaustiveness: the `when` compiles with no `else`, which is what makes a future fourth bound
        // a compile error at every consumer rather than a silent fall-through.
        val described = listOf(
            RecurrenceBound.Never,
            RecurrenceBound.OnDate(LocalDate(2027, 1, 31)),
            RecurrenceBound.AfterCount(10),
        ).map { bound ->
            when (bound) {
                RecurrenceBound.Never -> "never"
                is RecurrenceBound.OnDate -> "until ${bound.date}"
                is RecurrenceBound.AfterCount -> "${bound.n} times"
            }
        }
        assertEquals(listOf("never", "until 2027-01-31", "10 times"), described)
    }

    @Test
    fun theMonthlyAnchorDistinguishesADayFromAnNthWeekdayIncludingLast() {
        val fifteenth = MonthlyAnchor.DayOfMonth(15)
        val lastFriday = MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri")
        val secondWednesday = MonthlyAnchor.NthWeekday(nth = 2, weekday = "Wed")

        assertNotEquals<MonthlyAnchor>(fifteenth, lastFriday)
        assertNotEquals<MonthlyAnchor>(lastFriday, secondWednesday)
        // nth is an i8 on the wire; -1 is the documented "last" sentinel, not a corrupt value.
        assertEquals(-1, lastFriday.nth)

        val described = listOf(fifteenth, lastFriday).map { anchor ->
            when (anchor) {
                is MonthlyAnchor.DayOfMonth -> "day ${anchor.day}"
                is MonthlyAnchor.NthWeekday -> "${anchor.nth} ${anchor.weekday}"
            }
        }
        assertEquals(listOf("day 15", "-1 Fri"), described)
    }

    @Test
    fun rawTypePreservesAnUnmodellableCadenceInsteadOfDestroyingIt() {
        // The load-bearing half of #382's own scope note: "preserve what we can't render, so nothing is
        // destroyed by a round-trip". A rule this client version cannot model still carries its own name,
        // so the cache and the Backup file can hand it back rather than emitting the literal "Unknown".
        val future = Recurrence(RecurrenceFrequency.Unknown, rawType = "fortnightly")

        assertEquals("fortnightly", future.rawType)
        assertNotEquals(Recurrence(RecurrenceFrequency.Unknown), future)
        // It is Unknown-only by construction: a modelled cadence never carries one, which keeps a
        // hand-built `Recurrence(Daily)` equal to a `daily` rule read off the wire.
        assertNull(Recurrence(RecurrenceFrequency.Daily).rawType)
    }
}
