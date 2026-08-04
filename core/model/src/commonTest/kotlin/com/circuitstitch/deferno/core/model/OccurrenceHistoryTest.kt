package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Contract for the Occurrence history strip ([OccurrenceHistory.kt][StripCell]).
 *
 * This suite is a case-for-case reproduction of the web client's
 * `webui/src/utils/occurrenceHistory.test.ts` (242 lines at the reference checkout's HEAD), which is
 * the parity target ADR-0053 decision 5 names. Same pinned `today` (2026-05-18), same windows, same
 * expected numbers — so a divergence between the two clients shows up as a failing assertion here
 * rather than as two strips disagreeing on a person's screen. Cases that exist only here are marked;
 * they cover the members Kotlin's domain enums have and the TypeScript unions do not.
 *
 * `today` is pinned rather than read from a clock, for the reason [computeTileStrip]'s own KDoc gives:
 * a test that reached for the real clock would pass today and rot tomorrow, which is precisely the
 * defect the render-time contract exists to prevent.
 */
class OccurrenceHistoryTest {

    private val today = LocalDate(2026, 5, 18)

    private fun strip(
        statusByDate: Map<LocalDate, StripCellStatus> = emptyMap(),
        rangeDays: Int = 30,
    ): List<StripCell> = computeTileStrip(rangeDays, today, statusByDate)

    /** A day in May 2026 — the window every case below sits in. */
    private fun may(day: Int) = LocalDate(2026, 5, day)

    // ── addDays ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun addDaysAddsPositiveAndNegativeOffsets() {
        assertEquals(LocalDate(2026, 5, 19), LocalDate(2026, 5, 18).addDays(1))
        assertEquals(LocalDate(2026, 5, 17), LocalDate(2026, 5, 18).addDays(-1))
        assertEquals(LocalDate(2026, 5, 18), LocalDate(2026, 5, 18).addDays(0))
    }

    @Test
    fun addDaysCrossesMonthBoundariesInBothDirections() {
        assertEquals(LocalDate(2026, 6, 1), LocalDate(2026, 5, 31).addDays(1))
        assertEquals(LocalDate(2026, 5, 31), LocalDate(2026, 6, 1).addDays(-1))
    }

    @Test
    fun addDaysCrossesYearBoundariesInBothDirections() {
        assertEquals(LocalDate(2027, 1, 1), LocalDate(2026, 12, 31).addDays(1))
        assertEquals(LocalDate(2026, 12, 31), LocalDate(2027, 1, 1).addDays(-1))
    }

    /**
     * Kotlin-only: the calendar, not a month-length table, decides February. 2028 is a leap year, so
     * walking a day off 1 March lands on the 29th — the case a hand-rolled `-1` would get wrong.
     */
    @Test
    fun addDaysHonoursLeapFebruary() {
        assertEquals(LocalDate(2028, 2, 29), LocalDate(2028, 3, 1).addDays(-1))
        assertEquals(LocalDate(2027, 2, 28), LocalDate(2027, 3, 1).addDays(-1))
    }

    // ── bucketResolution — the stored five-variant fact ──────────────────────────────────────

    @Test
    fun bucketResolutionMapsTheTwoDoneVariantsToTheirOwnBuckets() {
        assertEquals(StripCellStatus.Done, bucketResolution(OccurrenceResolution.DoneOnTime))
        assertEquals(StripCellStatus.Late, bucketResolution(OccurrenceResolution.DoneLate))
    }

    /**
     * The web client's `bucketOccurrenceStatus("dropped")` and its defensive legacy-`"skipped"` arm
     * are one assertion here: both wire spellings decode to the single
     * [OccurrenceResolution.Skipped], so the tolerance lives at the wire boundary and this function
     * has one case to answer rather than two.
     */
    @Test
    fun bucketResolutionMapsSkippedTheOneSpellingOfDroppedToDropped() {
        assertEquals(StripCellStatus.Dropped, bucketResolution(OccurrenceResolution.Skipped))
    }

    @Test
    fun bucketResolutionMapsScheduledAndInProgressToScheduled() {
        assertEquals(StripCellStatus.Scheduled, bucketResolution(OccurrenceResolution.Scheduled))
        assertEquals(StripCellStatus.Scheduled, bucketResolution(OccurrenceResolution.InProgress))
    }

    /**
     * The structural replacement for the source's `default:` arm ("never claim done for something we
     * can't read"). An exhaustive `when` cannot be checked at runtime, so the invariant asserted here
     * is the one that survives: no resolution buckets to Done except the one that means done on time.
     */
    @Test
    fun bucketResolutionNeverClaimsDoneForAnythingButDoneOnTime() {
        val doneBuckets = OccurrenceResolution.entries.filter {
            bucketResolution(it) == StripCellStatus.Done
        }
        assertEquals(listOf(OccurrenceResolution.DoneOnTime), doneBuckets)
    }

    // ── bucketOccurrenceState — the derived reading ──────────────────────────────────────────

    @Test
    fun bucketOccurrenceStateMapsDoneOnTimeToDone() {
        assertEquals(StripCellStatus.Done, bucketOccurrenceState(OccurrenceState.DoneOnTime))
    }

    @Test
    fun bucketOccurrenceStateMapsDoneLateToLate() {
        assertEquals(StripCellStatus.Late, bucketOccurrenceState(OccurrenceState.DoneLate))
    }

    @Test
    fun bucketOccurrenceStateMapsMissedAndSkippedToDropped() {
        assertEquals(StripCellStatus.Dropped, bucketOccurrenceState(OccurrenceState.Missed))
        assertEquals(StripCellStatus.Dropped, bucketOccurrenceState(OccurrenceState.Skipped))
    }

    /**
     * Ported from the source's *body*, not its doc comment: `bucketChoreStatus`'s KDoc
     * (`occurrenceHistory.ts:78-82`) claims null for these two, its switch (`:94-99`) returns
     * `"scheduled"`, and its own test suite pins the switch. This assertion is that pin.
     */
    @Test
    fun bucketOccurrenceStateMapsScheduledAndInProgressToScheduledNotNull() {
        assertEquals(StripCellStatus.Scheduled, bucketOccurrenceState(OccurrenceState.Scheduled))
        assertEquals(StripCellStatus.Scheduled, bucketOccurrenceState(OccurrenceState.InProgress))
    }

    /** Kotlin-only: the reading TypeScript has no member for draws an empty tile, never a claim. */
    @Test
    fun bucketOccurrenceStateMapsUnknownToNoBucket() {
        assertNull(bucketOccurrenceState(OccurrenceState.Unknown))
    }

    // ── bucketHabitFact / bucketFact ─────────────────────────────────────────────────────────

    private fun fact(
        kind: ItemKind,
        resolution: OccurrenceResolution,
        doneAt: Instant? = null,
    ) = OccurrenceFact(
        kind = kind,
        definitionId = "def-1",
        date = may(17),
        resolution = resolution,
        doneAt = doneAt,
    )

    @Test
    fun bucketHabitFactMapsARecordedDoneAtToDone() {
        val done = fact(ItemKind.Habit, OccurrenceResolution.DoneOnTime, Instant.parse("2026-05-17T12:00:00Z"))
        assertEquals(StripCellStatus.Done, bucketHabitFact(done))
    }

    @Test
    fun bucketHabitFactMapsANullDoneAtToScheduled() {
        assertEquals(StripCellStatus.Scheduled, bucketHabitFact(fact(ItemKind.Habit, OccurrenceResolution.Scheduled)))
    }

    /**
     * The structural half of "a Habit strip never shows late or dropped": [bucketHabitFact] reads
     * `done_at` and nothing else, so a resolution the Habit wire cannot express does not leak through
     * even when one is present on the fact.
     */
    @Test
    fun bucketHabitFactIgnoresAResolutionTheHabitWireCannotExpress() {
        val late = fact(ItemKind.Habit, OccurrenceResolution.DoneLate, Instant.parse("2026-05-17T23:00:00Z"))
        assertEquals(StripCellStatus.Done, bucketHabitFact(late))
        assertEquals(StripCellStatus.Scheduled, bucketHabitFact(fact(ItemKind.Habit, OccurrenceResolution.Skipped)))
    }

    @Test
    fun bucketFactRoutesHabitsThroughDoneAtAndEveryOtherKindThroughItsResolution() {
        // Habit: routed on done_at, so a stored DoneLate still draws Done.
        val habit = fact(ItemKind.Habit, OccurrenceResolution.DoneLate, Instant.parse("2026-05-17T23:00:00Z"))
        assertEquals(StripCellStatus.Done, bucketFact(habit))
        // Chore and Event: routed on the stored resolution, which does carry punctuality.
        assertEquals(StripCellStatus.Late, bucketFact(fact(ItemKind.Chore, OccurrenceResolution.DoneLate)))
        assertEquals(StripCellStatus.Dropped, bucketFact(fact(ItemKind.Event, OccurrenceResolution.Skipped)))
        assertEquals(StripCellStatus.Scheduled, bucketFact(fact(ItemKind.Event, OccurrenceResolution.Scheduled)))
    }

    /**
     * Kotlin-only: a Task is not a recurring definition and produces no facts, so this shape cannot
     * arise. It is pinned anyway because the arm exists — a total function that reads its resolution
     * is a better answer than one that throws on a row a corrupted cache could still hand it.
     */
    @Test
    fun bucketFactReadsATaskShapedFactByItsResolutionRatherThanRefusing() {
        assertEquals(StripCellStatus.Done, bucketFact(fact(ItemKind.Task, OccurrenceResolution.DoneOnTime)))
    }

    // ── computeTileStrip ─────────────────────────────────────────────────────────────────────

    @Test
    fun computeTileStripReturnsRangeDaysCellsOldestFirstTodayLast() {
        val cells = strip(rangeDays = 5)
        assertEquals(5, cells.size)
        assertEquals(may(14), cells[0].date)
        assertEquals(today, cells[4].date)
        assertTrue(cells[4].isToday)
        assertFalse(cells[0].isToday)
    }

    @Test
    fun computeTileStripAttachesStatusesFromTheMapByDate() {
        val byDate = mapOf(
            may(16) to StripCellStatus.Done,
            may(17) to StripCellStatus.Late,
            today to StripCellStatus.Dropped,
        )
        assertEquals(
            listOf(null, null, StripCellStatus.Done, StripCellStatus.Late, StripCellStatus.Dropped),
            strip(byDate, rangeDays = 5).map { it.status },
        )
    }

    @Test
    fun computeTileStripSupportsThirtySixtyAndNinetyDayRanges() {
        assertEquals(30, strip(rangeDays = 30).size)
        assertEquals(60, strip(rangeDays = 60).size)
        assertEquals(90, strip(rangeDays = 90).size)
        // The right edge stays anchored at today however wide the window: only history extends.
        for (days in listOf(30, 60, 90)) {
            assertEquals(today, strip(rangeDays = days).last().date)
        }
        assertEquals(today.addDays(-89), strip(rangeDays = 90).first().date)
    }

    /** The anchoring claim, asserted rather than assumed: no cell this function builds is future. */
    @Test
    fun computeTileStripNeverBuildsAFutureCell() {
        assertTrue(strip(rangeDays = 90).none { it.isFuture })
        assertEquals(1, strip(rangeDays = 90).count { it.isToday })
    }

    /** Kotlin-only: an empty window is coherent, not an error, and the stats read zero over it. */
    @Test
    fun computeTileStripYieldsAnEmptyStripForANonPositiveRange() {
        assertEquals(emptyList(), strip(rangeDays = 0))
        assertEquals(emptyList(), strip(rangeDays = -3))
    }

    /** The window walks the calendar, so a 5-day strip anchored on 2 June reaches back into May. */
    @Test
    fun computeTileStripCrossesAMonthBoundaryBackwards() {
        val cells = computeTileStrip(rangeDays = 5, today = LocalDate(2026, 6, 2), statusByDate = emptyMap())
        assertEquals(LocalDate(2026, 5, 29), cells[0].date)
        assertEquals(LocalDate(2026, 6, 2), cells[4].date)
    }

    // ── computeHeatmapStats ──────────────────────────────────────────────────────────────────

    @Test
    fun computeHeatmapStatsCountsOnTimeLateAndDroppedAndTotalsTheThree() {
        val byDate = mapOf(
            may(14) to StripCellStatus.Done,
            may(15) to StripCellStatus.Done,
            may(16) to StripCellStatus.Late,
            may(17) to StripCellStatus.Dropped,
            today to StripCellStatus.Scheduled,
        )
        val stats = computeHeatmapStats(strip(byDate, rangeDays = 5))
        assertEquals(HeatmapStats(onTime = 2, late = 1, dropped = 1), stats)
        assertEquals(4, stats.total)
    }

    @Test
    fun computeHeatmapStatsIsZeroEverywhereForAnEmptyStrip() {
        val stats = computeHeatmapStats(strip(rangeDays = 5))
        assertEquals(HeatmapStats(onTime = 0, late = 0, dropped = 0), stats)
        assertEquals(0, stats.total)
    }

    /**
     * The load-bearing case: an unresolved day is evidence of nothing, so it moves neither a bucket
     * nor the total. Counting it would silently deflate every rate computed downstream.
     */
    @Test
    fun computeHeatmapStatsScheduledAndEmptyCellsDoNotContributeToTotal() {
        val byDate = mapOf(
            may(14) to StripCellStatus.Scheduled,
            may(15) to StripCellStatus.Scheduled,
        )
        val stats = computeHeatmapStats(strip(byDate, rangeDays = 5))
        assertEquals(HeatmapStats(onTime = 0, late = 0, dropped = 0), stats)
        assertEquals(0, stats.total)
    }

    /**
     * A literally empty list, which is a different input from the 5-cell all-null window above: it is
     * what [computeTileStrip] returns for a non-positive range, so every reader below it must survive
     * one. Both scans read zero rather than reaching for a first or last cell.
     */
    @Test
    fun theStatsAndTheStreakBothSurviveALiterallyEmptyList() {
        assertEquals(HeatmapStats(onTime = 0, late = 0, dropped = 0), computeHeatmapStats(emptyList()))
        assertEquals(HabitStreak(current = 0, best = 0), computeHabitStreak(emptyList()))
        assertNull(computeOnTimeRate(computeHeatmapStats(emptyList())))
    }

    // ── computeOnTimeRate ────────────────────────────────────────────────────────────────────

    @Test
    fun computeOnTimeRateIsOnTimeOverOnTimePlusLateRoundedToAWholePercent() {
        assertEquals(86, computeOnTimeRate(HeatmapStats(onTime = 6, late = 1, dropped = 1)))
        assertEquals(100, computeOnTimeRate(HeatmapStats(onTime = 10, late = 0, dropped = 0)))
        assertEquals(33, computeOnTimeRate(HeatmapStats(onTime = 1, late = 2, dropped = 0)))
    }

    /**
     * Dropped is deliberately outside the denominator: the rate answers "when this got done, was it
     * on time", which is a question about punctuality and not about adherence. Five drops beside a
     * clean record therefore leave the rate at 100.
     */
    @Test
    fun computeOnTimeRateKeepsDroppedOutOfTheDenominator() {
        assertEquals(100, computeOnTimeRate(HeatmapStats(onTime = 4, late = 0, dropped = 5)))
    }

    /** Null, never zero: nothing done and nothing late is *no data*, and 0% reads as total failure. */
    @Test
    fun computeOnTimeRateIsNullNotZeroWhenTheDenominatorIsZero() {
        assertNull(computeOnTimeRate(HeatmapStats(onTime = 0, late = 0, dropped = 5)))
        assertNull(computeOnTimeRate(HeatmapStats(onTime = 0, late = 0, dropped = 0)))
    }

    // ── computeHabitStreak ───────────────────────────────────────────────────────────────────

    @Test
    fun computeHabitStreakCountsConsecutiveDoneDaysFromTodayBackwards() {
        val byDate = (14..18).associate { may(it) to StripCellStatus.Done }
        val streak = computeHabitStreak(strip(byDate, rangeDays = 5))
        assertEquals(5, streak.current)
        assertEquals(5, streak.best)
        assertEquals(5, streak.delta)
    }

    @Test
    fun computeHabitStreakStopsAtTheFirstNonDoneCell() {
        val byDate = mapOf(
            may(14) to StripCellStatus.Done,
            may(15) to StripCellStatus.Done,
            may(16) to StripCellStatus.Scheduled,
            may(17) to StripCellStatus.Done,
            today to StripCellStatus.Done,
        )
        val streak = computeHabitStreak(strip(byDate, rangeDays = 5))
        assertEquals(2, streak.current)
        assertEquals(2, streak.best)
    }

    @Test
    fun computeHabitStreakBestTracksTheLongestDoneRunAnywhereInTheWindow() {
        val byDate = mapOf(
            may(13) to StripCellStatus.Done,
            may(14) to StripCellStatus.Done,
            may(15) to StripCellStatus.Done,
            may(16) to StripCellStatus.Scheduled,
            today to StripCellStatus.Done,
        )
        val streak = computeHabitStreak(strip(byDate, rangeDays = 6))
        assertEquals(1, streak.current)
        assertEquals(3, streak.best)
    }

    @Test
    fun computeHabitStreakIsZeroOnAnEmptyWindow() {
        val streak = computeHabitStreak(strip(rangeDays = 5))
        assertEquals(HabitStreak(current = 0, best = 0), streak)
        assertEquals(0, streak.delta)
    }

    /**
     * The case that matters most, and the one a single fused scan gets wrong: a done run that stops
     * the day *before* today leaves the current streak at zero while `best` keeps the record. An
     * empty cell in the past is not a check-in.
     */
    @Test
    fun computeHabitStreakARunEndingYesterdayGivesZeroCurrentAndKeepsBest() {
        val byDate = mapOf(
            may(15) to StripCellStatus.Done,
            may(16) to StripCellStatus.Done,
            may(17) to StripCellStatus.Done,
        )
        val streak = computeHabitStreak(strip(byDate, rangeDays = 5))
        assertEquals(0, streak.current)
        assertEquals(3, streak.best)
        assertEquals(0, streak.delta)
    }

    /**
     * Kotlin-only, over a hand-built strip [computeTileStrip] would never produce: future cells are
     * skipped rather than breaking the walk, so a strip extending past today still reads its streak
     * from today backwards. This is the source's `if (c.isFuture) continue`.
     */
    @Test
    fun computeHabitStreakSkipsFutureCellsRatherThanBreakingOnThem() {
        val cells = listOf(
            StripCell(may(16), StripCellStatus.Done, isToday = false, isFuture = false),
            StripCell(may(17), StripCellStatus.Done, isToday = false, isFuture = false),
            StripCell(today, StripCellStatus.Done, isToday = true, isFuture = false),
            StripCell(may(19), null, isToday = false, isFuture = true),
            StripCell(may(20), null, isToday = false, isFuture = true),
        )
        val streak = computeHabitStreak(cells)
        assertEquals(3, streak.current)
        assertEquals(3, streak.best)
    }
}
