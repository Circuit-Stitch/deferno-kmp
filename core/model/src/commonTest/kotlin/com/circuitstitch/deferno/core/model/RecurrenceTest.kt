package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The domain contract for [Recurrence] (#382). The rule this type has to keep is that **whatever the
 * domain cannot say is destroyed by a cache round-trip**, because the cached row is written *from*
 * here: before the widening the domain could express only "a frequency and, if weekly, some days", so
 * `every_n_days` and `custom` had no representation at all and `monthly`/`yearly` kept their name while
 * losing every parameter that made the rule mean anything.
 *
 * These pin what the sealed [Cadence] buys over the flat bag it replaced: every cadence carries exactly
 * its own parameters (so two rules differing only in one are genuinely different values), the `when`
 * over it is exhaustive with no `else` (so a seventh backend cadence is a compile error at every
 * consumer rather than a silent fall-through), the bound is orthogonal and defaults to unbounded, and
 * [Cadence.Unmodelled] cannot be built without the token it is preserving.
 */
class RecurrenceTest {

    @Test
    fun theWhenOverACadenceIsExhaustiveAndEachVariantOwnsItsParameters() {
        // The `when` below has NO `else`. That is the whole point of the sealed type: a seventh backend
        // cadence stops the build at every consumer instead of quietly falling through to a default —
        // which is exactly how `every_n_days` and `custom` used to disappear.
        val described = listOf(
            Cadence.Daily,
            Cadence.EveryNDays(3),
            Cadence.Weekly(listOf("Mon", "Wed")),
            Cadence.Monthly(interval = 2, on = MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri")),
            // A monthly rule whose anchor could not be read is still a usable monthly rule.
            Cadence.Monthly(interval = 1),
            Cadence.Yearly(interval = 1, month = 6, day = 14),
            Cadence.Custom("FREQ=WEEKLY;BYDAY=MO"),
            Cadence.Unmodelled("fortnightly"),
        ).map { cadence ->
            when (cadence) {
                Cadence.Daily -> "daily"
                is Cadence.EveryNDays -> "every ${cadence.n} days"
                is Cadence.Weekly -> cadence.days.joinToString("/")
                is Cadence.Monthly -> when (val on = cadence.on) {
                    null -> "every ${cadence.interval} months"
                    is MonthlyAnchor.DayOfMonth -> "every ${cadence.interval} months on day ${on.day}"
                    is MonthlyAnchor.NthWeekday -> "every ${cadence.interval} months on ${on.nth} ${on.weekday}"
                }
                is Cadence.Yearly -> "every ${cadence.interval} years on ${cadence.month}/${cadence.day}"
                is Cadence.Custom -> cadence.rrule
                is Cadence.Unmodelled -> cadence.rawType
            }
        }

        assertEquals(
            listOf(
                "daily",
                "every 3 days",
                "Mon/Wed",
                "every 2 months on -1 Fri",
                "every 1 months",
                "every 1 years on 6/14",
                "FREQ=WEEKLY;BYDAY=MO",
                "fortnightly",
            ),
            described,
        )
    }

    @Test
    fun everyCadenceIsDistinctIncludingTwoDifferingOnlyInOneParameter() {
        // The backend's `Cadence` is a closed six-variant enum; Unmodelled is a seventh, client-only arm
        // for a cadence a future backend adds. Two rules that differ only in a parameter must not be
        // equal — the every-30-days vs every-29-days indistinguishability was the reported symptom.
        val rules = listOf(
            Recurrence(Cadence.Daily),
            Recurrence(Cadence.EveryNDays(29)),
            Recurrence(Cadence.EveryNDays(30)),
            Recurrence(Cadence.Weekly(listOf("Tue"))),
            Recurrence(Cadence.Weekly(listOf("Wed"))),
            Recurrence(Cadence.Monthly(interval = 1, on = MonthlyAnchor.DayOfMonth(15))),
            Recurrence(Cadence.Monthly(interval = 1)),
            Recurrence(Cadence.Yearly(interval = 1, month = 6, day = 14)),
            Recurrence(Cadence.Custom("FREQ=WEEKLY;BYDAY=MO")),
            Recurrence(Cadence.Unmodelled("fortnightly")),
        )

        assertEquals(rules.size, rules.toSet().size, "every rule is distinguishable from the others")
        assertNotEquals(rules[1], rules[2], "every 29 days is not every 30 days")
    }

    @Test
    fun aRuleIsUnboundedUnlessItSaysOtherwise() {
        // An absent wire `end` IS the never bound, so Never has to be the DEFAULT rather than something
        // every construction site must remember to pass.
        assertEquals(RecurrenceBound.Never, Recurrence(Cadence.Daily).bound)
        assertEquals(Recurrence(Cadence.Daily), Recurrence(Cadence.Daily, bound = RecurrenceBound.Never))
        assertNotEquals(
            Recurrence(Cadence.Daily),
            Recurrence(Cadence.Daily, bound = RecurrenceBound.AfterCount(10)),
        )

        // Cadence and bound are orthogonal — the same bound rides any cadence — which is why the bound
        // is a peer of the cadence here rather than a field repeated inside each variant.
        assertEquals(
            RecurrenceBound.AfterCount(10),
            Recurrence(Cadence.Custom("FREQ=DAILY"), RecurrenceBound.AfterCount(10)).bound,
        )
    }

    @Test
    fun anUnmodelledCadenceCannotBeBuiltWithoutTheTokenItIsPreserving() {
        // The load-bearing half of #382's own scope note: "preserve what we can't render, so nothing is
        // destroyed by a round-trip". A rule this build cannot model still carries its own name, so the
        // cache and the Backup file hand it back instead of a literal "Unknown".
        val fortnightly = Recurrence(Cadence.Unmodelled("fortnightly"))

        assertEquals("fortnightly", (fortnightly.cadence as Cadence.Unmodelled).rawType)
        // Two unmodelled cadences are different rules, not one anonymous "we don't know" bucket.
        assertNotEquals(fortnightly, Recurrence(Cadence.Unmodelled("bimonthly")))
        // `rawType` is a required constructor parameter. Its flat predecessor let `rawType` default to
        // null on ANY frequency, and that silent hole is what made a backup of such an item unrestorable.
    }

    @Test
    fun theBoundIsATotalSealedHierarchyOfThreeCases() {
        assertIs<RecurrenceBound>(RecurrenceBound.Never)
        assertEquals(RecurrenceBound.OnDate(LocalDate(2027, 1, 31)), RecurrenceBound.OnDate(LocalDate(2027, 1, 31)))
        assertEquals(RecurrenceBound.AfterCount(10), RecurrenceBound.AfterCount(10))
        assertNotEquals<RecurrenceBound>(RecurrenceBound.AfterCount(10), RecurrenceBound.AfterCount(11))

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
}
