package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The client-side half of [expandOccurrenceGrid]'s contract — the part the server has no opinion
 * about, and which therefore cannot come from the corpus:
 *
 * - **which refusal** a bad input takes ([ExpansionRefusal] is a client taxonomy);
 * - the refusals the server never even reaches, because it degrades an unexpandable series to zero
 *   occurrences instead (`expand_series_resilient`) and because it has no `Custom` guard at all;
 * - that **"no grid" and "an empty grid" are different values**, which is the property ADR-0053
 *   decision 4 turns on.
 *
 * Every *grid* fact — which dates fire — lives in [RecurrenceCorpusTest] and is generated from the
 * Rust. Nothing here asserts a firing date that is not trivially the anchor's own.
 */
class OccurrenceGridTest {

    private val la = "America/Los_Angeles"
    private val anchor = LocalDateTime.parse("2026-04-01T09:00:00")
    private val window = LocalDate.parse("2026-04-01")..LocalDate.parse("2026-04-30")

    private fun expand(
        cadence: Cadence,
        bound: RecurrenceBound = RecurrenceBound.Never,
        series: SeriesInputs = SeriesInputs(anchorLocal = anchor, tzid = la),
        from: LocalDate = window.start,
        to: LocalDate = window.endInclusive,
    ) = expandOccurrenceGrid(Recurrence(cadence, bound), series, from, to)

    private fun refusalOf(expansion: Expansion): ExpansionRefusal =
        assertIs<Expansion.NotExpandable>(expansion).reason

    // ── "Absent" and "empty" are different values ─────────────────────────────────────────────────

    @Test
    fun anUnexpandableGridIsNotAnEmptyOne() {
        val unexpandable = expand(Cadence.Custom("FREQ=HOURLY"))
        // A daily rule read over a window that closes before it starts: a real, computed, empty grid.
        val empty = expand(
            Cadence.Daily,
            from = LocalDate.parse("2025-01-01"),
            to = LocalDate.parse("2025-12-31"),
        )

        assertIs<Expansion.NotExpandable>(unexpandable)
        val firings = assertIs<Expansion.Firings>(empty)
        assertEquals(emptyList(), firings.firings)
        // The point: no caller can reach an empty list without having matched Firings first, so the
        // two can never render the same by accident.
        assertNotEquals<Expansion>(unexpandable, empty)
    }

    @Test
    fun aWindowWithNoFiringsIsStillAGrid() {
        // A rule that fires in no year that will ever exist. The server answers this with an empty
        // list rather than an error, and so must we — see the corpus case of the same shape.
        val expansion = expand(Cadence.Yearly(interval = 1, month = 2, day = 30))
        assertEquals(emptyList(), assertIs<Expansion.Firings>(expansion).firings)
    }

    // ── The refusals ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun aCustomRuleIsRefused() {
        assertEquals(ExpansionRefusal.CustomCadence, refusalOf(expand(Cadence.Custom("FREQ=HOURLY"))))
    }

    @Test
    fun anUnmodelledCadenceIsRefusedUnderItsOwnName() {
        assertEquals(
            ExpansionRefusal.UnmodelledCadence("lunar"),
            refusalOf(expand(Cadence.Unmodelled("lunar"))),
        )
    }

    @Test
    fun aMonthlyRuleWithNoAnchorIsRefusedRatherThanGuessedFromTheSeriesAnchor() {
        // The anchor here is the 1st. Guessing BYMONTHDAY=1 from it would invent a grid the server
        // does not have — the Rust makes the anchor mandatory, so an absent one has no counterpart.
        assertEquals(
            ExpansionRefusal.MonthlyWithoutAnchor,
            refusalOf(expand(Cadence.Monthly(interval = 1, on = null))),
        )
    }

    @Test
    fun anNthOutsideTheCratesRangeIsRefused() {
        // `rrule` rejects BYDAY nth outside -4..5 as a validation error rather than expanding.
        val anchor = MonthlyAnchor.NthWeekday(nth = 6, weekday = "Thu")
        assertEquals(
            ExpansionRefusal.UnplaceableMonthlyAnchor(anchor),
            refusalOf(expand(Cadence.Monthly(interval = 1, on = anchor))),
        )
        assertIs<ExpansionRefusal.UnplaceableMonthlyAnchor>(
            refusalOf(expand(Cadence.Monthly(1, MonthlyAnchor.DayOfMonth(day = 32)))),
        )
    }

    @Test
    fun aYearlyDateOutsideTheCratesRangeIsRefused() {
        // Both are `rrule` PARSE errors, so the rule fails rather than firing nothing — which is what
        // separates them from `BYMONTH=2;BYMONTHDAY=30`, a rule that parses and legitimately never
        // matches. Neither side range-checks the field, so a hand-built payload reaches this.
        assertEquals(
            ExpansionRefusal.UnplaceableYearlyDate(month = 13, day = 5),
            refusalOf(expand(Cadence.Yearly(interval = 1, month = 13, day = 5))),
        )
        assertEquals(
            ExpansionRefusal.UnplaceableYearlyDate(month = 6, day = 32),
            refusalOf(expand(Cadence.Yearly(interval = 1, month = 6, day = 32))),
        )
    }

    @Test
    fun theZeroValuedAnchorsAreGridsNotRefusals() {
        // Both mean "no day part", which makes the rule take its day from the anchor rather than
        // making it unplaceable — and an `nth` of zero means EVERY such weekday. Refusing either
        // would hide a live schedule; the dates themselves are pinned by the corpus, so this asserts
        // only which side of the Firings/NotExpandable line they fall on.
        assertTrue(assertIs<Expansion.Firings>(expand(Cadence.Monthly(1, MonthlyAnchor.DayOfMonth(0)))).firings.isNotEmpty())
        assertTrue(assertIs<Expansion.Firings>(expand(Cadence.Monthly(1, MonthlyAnchor.NthWeekday(0, "Wed")))).firings.isNotEmpty())
        assertTrue(assertIs<Expansion.Firings>(expand(Cadence.Yearly(1, 4, 0))).firings.isNotEmpty())
    }

    @Test
    fun aWeeklyRuleWithNoReadableDaysIsRefused() {
        assertEquals(
            ExpansionRefusal.UnplaceableWeekday(emptyList()),
            refusalOf(expand(Cadence.Weekly(emptyList()))),
        )
        // Partial is not good enough: one unreadable token refuses the whole grid rather than
        // silently shipping a schedule missing a day (ADR-0053 — absent, not empty).
        assertEquals(
            ExpansionRefusal.UnplaceableWeekday(listOf("Mon", "Zwo")),
            refusalOf(expand(Cadence.Weekly(listOf("Mon", "Zwo")))),
        )
    }

    @Test
    fun aRepeatedWeekdayIsLegalAndCollapses() {
        val once = expand(Cadence.Weekly(listOf("Wed")))
        val twice = expand(Cadence.Weekly(listOf("Wed", "Wed")))
        assertEquals(once, twice)
        assertTrue(assertIs<Expansion.Firings>(once).firings.isNotEmpty())
    }

    @Test
    fun aZoneThisBuildCannotPlaceIsRefusedRatherThanThrowing() {
        assertEquals(
            ExpansionRefusal.UnknownTimeZone("Mars/Olympus_Mons"),
            refusalOf(expand(Cadence.Daily, series = SeriesInputs(anchor, "Mars/Olympus_Mons"))),
        )
    }

    @Test
    fun anAnchorInADaylightSavingGapOrFoldIsRefused() {
        // Both fail the crate's DTSTART parser, which is stricter than its iterator: it rejects an
        // ambiguous wall time as firmly as a nonexistent one. Reachable in production, because
        // `deadline_time_of_day` can put a series anchor at 01:30 on a fall-back day.
        val inGap = LocalDateTime.parse("2026-03-08T02:30:00")
        assertEquals(
            ExpansionRefusal.AnchorNotResolvable(inGap),
            refusalOf(expand(Cadence.Daily, series = SeriesInputs(inGap, "America/New_York"))),
        )
        val inFold = LocalDateTime.parse("2026-11-01T01:30:00")
        assertEquals(
            ExpansionRefusal.AnchorNotResolvable(inFold),
            refusalOf(expand(Cadence.Daily, series = SeriesInputs(inFold, "America/New_York"))),
        )
    }

    @Test
    fun anUnresolvableExcludedDateRefusesTheWholeGridRatherThanBeingIgnored() {
        val exdate = LocalDateTime.parse("2026-03-08T02:30:00")
        assertEquals(
            ExpansionRefusal.ExcludedDateNotResolvable(exdate),
            refusalOf(
                expand(
                    Cadence.Daily,
                    series = SeriesInputs(
                        anchorLocal = LocalDateTime.parse("2026-03-06T09:00:00"),
                        tzid = "America/New_York",
                        exdates = listOf(exdate),
                    ),
                    from = LocalDate.parse("2026-03-01"),
                    to = LocalDate.parse("2026-03-14"),
                ),
            ),
        )
    }

    @Test
    fun anAnchorPastTheRuleBoundIsRefused() {
        assertEquals(
            ExpansionRefusal.AnchorAfterBound,
            refusalOf(expand(Cadence.Daily, RecurrenceBound.OnDate(LocalDate.parse("2025-01-01")))),
        )
    }

    // ── Shape ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMovedFiringRendersOnItsNewDayButKeepsItsIdentityOnTheOldOne() {
        val slot = LocalDateTime.parse("2026-04-08T09:00:00")
        val expansion = expand(
            Cadence.Weekly(listOf("Wed")),
            series = SeriesInputs(
                anchorLocal = anchor,
                tzid = la,
                overrides = listOf(SeriesOverride(slot, movedToLocal = LocalDateTime.parse("2026-04-09T14:00:00"))),
            ),
        )
        val moved = assertIs<Expansion.Firings>(expansion).firings.single { it.isOverride }
        // `date` is where it renders; `slotDate` is the day `OccurrenceTargets.of` keys on, which
        // stays with the slot it was moved FROM. Conflating them addresses the wrong occurrence.
        assertEquals(LocalDate.parse("2026-04-09"), moved.date)
        assertEquals(LocalDate.parse("2026-04-08"), moved.slotDate)
    }

    @Test
    fun aCancelledFiringIsPresentAndFlaggedNotAbsent() {
        val slot = LocalDateTime.parse("2026-04-08T09:00:00")
        val expansion = expand(
            Cadence.Weekly(listOf("Wed")),
            series = SeriesInputs(anchor, la, overrides = listOf(SeriesOverride(slot, isCancelled = true))),
        )
        val firings = assertIs<Expansion.Firings>(expansion).firings
        assertEquals(listOf(slot), firings.filter { it.isCancelled }.map { it.recurrenceId })
    }

    @Test
    fun theSegmentBoundAndTheRuleBoundAreIndependent() {
        // Both live at once on a superseded segment, with opposite inclusivity. Here only the segment
        // bound is set, and it must bite on its own.
        val expansion = expand(
            Cadence.Daily,
            series = SeriesInputs(anchor, la, untilUtc = Instant.parse("2026-04-05T16:00:00Z")),
        )
        val firings = assertIs<Expansion.Firings>(expansion).firings
        assertEquals(LocalDate.parse("2026-04-04"), firings.last().date)
    }

    @Test
    fun anInvertedWindowIsACallerBugNotAnEmptyGrid() {
        assertFailsWith<IllegalArgumentException> {
            expand(Cadence.Daily, from = window.endInclusive, to = window.start)
        }
    }

    @Test
    fun expansionIsPureAndRepeatable() {
        // No clock read, no device zone, no I/O — so the same inputs give the same answer forever.
        // A value that changed between calls is the whole defect this replaces.
        assertEquals(expand(Cadence.Daily), expand(Cadence.Daily))
    }

    @Test
    fun aStrideOfZeroFiresNothingRatherThanLoopingForever() {
        // `INTERVAL=0` is not rejected by the crate; it simply yields nothing.
        assertEquals(emptyList(), assertIs<Expansion.Firings>(expand(Cadence.EveryNDays(0))).firings)
    }
}
