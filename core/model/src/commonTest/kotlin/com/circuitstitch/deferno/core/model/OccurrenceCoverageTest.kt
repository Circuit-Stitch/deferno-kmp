package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for [OccurrenceCoverage] and [mergeCoverage] — the bookkeeping that makes "there is no row
 * for 3 March" answerable (CONTEXT.md → "Occurrence coverage").
 *
 * The merge is the one place this slice can silently reintroduce the exact defect ADR-0053 was written
 * to close. Coalescing two synced windows that have a gap between them would swallow the gap into
 * "synced", and every unsynced day inside it would then feed [resolveOccurrenceState]'s past-and-
 * covered arm and read as Missed instead of Unknown — the defect one layer down, invisible from every
 * surface. So the gap case below is the load-bearing test in this file, and the adjacency case is what
 * stops the fix from over-correcting into a list that never coalesces at all.
 */
class OccurrenceCoverageTest {

    private fun june(day: Int) = LocalDate(2026, 6, day)

    private fun coverage(
        from: LocalDate,
        to: LocalDate,
        kind: ItemKind = ItemKind.Habit,
        definitionId: String = "hab-1",
    ) = OccurrenceCoverage(kind, definitionId, from, to)

    // ── One range ────────────────────────────────────────────────────────────────────────────

    /** Both bounds are inclusive — this records exactly the `?from=&to=` the endpoint was asked for. */
    @Test
    fun bothBoundsAreInclusive() {
        val range = coverage(june(10), june(20))
        assertTrue(range.covers(june(10)))
        assertTrue(range.covers(june(20)))
        assertTrue(range.covers(june(15)))
        assertFalse(range.covers(june(9)))
        assertFalse(range.covers(june(21)))
    }

    /** A single-day window is legal: syncing one day covers exactly that day. */
    @Test
    fun aSingleDayWindowCoversThatOneDay() {
        val range = coverage(june(10), june(10))
        assertTrue(range.covers(june(10)))
        assertFalse(range.covers(june(11)))
    }

    /**
     * An inverted range is rejected at construction rather than silently covering nothing. A range
     * that quietly covered no days would make every date inside the window it *meant* read Unknown,
     * which is a wrong answer that looks like a cautious one.
     */
    @Test
    fun anInvertedRangeIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { coverage(june(20), june(10)) }
    }

    // ── mergeCoverage: the gap ───────────────────────────────────────────────────────────────

    /**
     * **The case that matters.** Two windows with a single unsynced day between them stay two
     * ranges. Merging them would assert that 15 June was fetched when it was not, and the reading for
     * that day would flip from Unknown to Missed on an Active definition — inventing a reproach out
     * of a sync gap.
     */
    @Test
    fun aOneDayGapDoesNotMerge() {
        val existing = listOf(coverage(june(10), june(14)))
        val merged = existing.mergeCoverage(coverage(june(16), june(20)))

        assertEquals(listOf(coverage(june(10), june(14)), coverage(june(16), june(20))), merged)
        assertFalse(merged.covers(ItemKind.Habit, "hab-1", june(15)))
        assertTrue(merged.covers(ItemKind.Habit, "hab-1", june(14)))
        assertTrue(merged.covers(ItemKind.Habit, "hab-1", june(16)))
    }

    /** A wide gap is the same rule, and the ranges stay sorted so the result is deterministic. */
    @Test
    fun aWideGapLeavesBothRangesIntactAndSorted() {
        val merged = listOf(coverage(june(20), june(25)))
            .mergeCoverage(coverage(june(1), june(5)))

        assertEquals(listOf(coverage(june(1), june(5)), coverage(june(20), june(25))), merged)
        assertFalse(merged.covers(ItemKind.Habit, "hab-1", june(12)))
    }

    /** Several disjoint windows all survive a further disjoint one — nothing is dropped or fused. */
    @Test
    fun aThirdDisjointWindowJoinsWithoutDisturbingTheOthers() {
        val merged = listOf(coverage(june(1), june(3)), coverage(june(10), june(12)))
            .mergeCoverage(coverage(june(20), june(22)))

        assertEquals(
            listOf(coverage(june(1), june(3)), coverage(june(10), june(12)), coverage(june(20), june(22))),
            merged,
        )
    }

    // ── mergeCoverage: touching ──────────────────────────────────────────────────────────────

    /**
     * Adjacency *does* merge: a range ending the day before the next begins leaves no unsynced day
     * between them, so joining them asserts nothing that was not actually fetched. Without this the
     * list would grow one entry per sync forever and never coalesce.
     */
    @Test
    fun genuinelyAdjacentRangesMerge() {
        val merged = listOf(coverage(june(10), june(14)))
            .mergeCoverage(coverage(june(15), june(20)))

        assertEquals(listOf(coverage(june(10), june(20))), merged)
        assertTrue(merged.covers(ItemKind.Habit, "hab-1", june(15)))
    }

    /** Adjacency is decided by the calendar, so it holds across a month end. */
    @Test
    fun adjacencyHoldsAcrossAMonthEnd() {
        val merged = listOf(OccurrenceCoverage(ItemKind.Habit, "hab-1", LocalDate(2026, 5, 20), LocalDate(2026, 5, 31)))
            .mergeCoverage(OccurrenceCoverage(ItemKind.Habit, "hab-1", LocalDate(2026, 6, 1), LocalDate(2026, 6, 5)))

        assertEquals(
            listOf(OccurrenceCoverage(ItemKind.Habit, "hab-1", LocalDate(2026, 5, 20), LocalDate(2026, 6, 5))),
            merged,
        )
    }

    @Test
    fun overlappingRangesMergeIntoTheirUnion() {
        val merged = listOf(coverage(june(10), june(20)))
            .mergeCoverage(coverage(june(15), june(25)))

        assertEquals(listOf(coverage(june(10), june(25))), merged)
    }

    /** A window entirely inside an existing one is absorbed and widens nothing. */
    @Test
    fun aContainedRangeIsAbsorbed() {
        val merged = listOf(coverage(june(1), june(30)))
            .mergeCoverage(coverage(june(10), june(12)))

        assertEquals(listOf(coverage(june(1), june(30))), merged)
    }

    /** A window that swallows an existing one replaces it rather than sitting beside it. */
    @Test
    fun aSupersetRangeSwallowsTheExistingOne() {
        val merged = listOf(coverage(june(10), june(12)))
            .mergeCoverage(coverage(june(1), june(30)))

        assertEquals(listOf(coverage(june(1), june(30))), merged)
    }

    /**
     * One new window that bridges two existing ones collapses all three into a single span — the case
     * a naive fold over the *first* touching range would leave half-merged.
     */
    @Test
    fun aBridgingRangeCollapsesBothNeighboursIntoOne() {
        val merged = listOf(coverage(june(1), june(5)), coverage(june(20), june(25)))
            .mergeCoverage(coverage(june(4), june(21)))

        assertEquals(listOf(coverage(june(1), june(25))), merged)
    }

    /** Recording the same window twice is idempotent. */
    @Test
    fun recordingTheSameWindowTwiceChangesNothing() {
        val once = emptyList<OccurrenceCoverage>().mergeCoverage(coverage(june(10), june(20)))
        assertEquals(once, once.mergeCoverage(coverage(june(10), june(20))))
    }

    @Test
    fun mergingIntoAnEmptyListYieldsTheOneRange() {
        assertEquals(
            listOf(coverage(june(10), june(20))),
            emptyList<OccurrenceCoverage>().mergeCoverage(coverage(june(10), june(20))),
        )
    }

    // ── mergeCoverage: other definitions ─────────────────────────────────────────────────────

    /**
     * Coverage is per definition, so another definition's ranges pass through untouched — even when
     * their dates overlap. Fusing across definitions would claim one definition was synced because a
     * different one was, which is the gap defect wearing another axis.
     */
    @Test
    fun anotherDefinitionsRangesPassThroughUntouched() {
        val other = coverage(june(1), june(30), definitionId = "hab-2")
        val merged = listOf(other).mergeCoverage(coverage(june(10), june(20)))

        assertEquals(setOf(other, coverage(june(10), june(20))), merged.toSet())
        assertFalse(merged.covers(ItemKind.Habit, "hab-1", june(5)))
        assertTrue(merged.covers(ItemKind.Habit, "hab-2", june(5)))
    }

    /** The same id under a different kind is a different definition, and does not merge either. */
    @Test
    fun theSameIdUnderADifferentKindIsADifferentDefinition() {
        val chore = coverage(june(1), june(30), kind = ItemKind.Chore)
        val merged = listOf(chore).mergeCoverage(coverage(june(10), june(20), kind = ItemKind.Habit))

        assertEquals(setOf(chore, coverage(june(10), june(20))), merged.toSet())
        assertFalse(merged.covers(ItemKind.Habit, "hab-1", june(5)))
        assertTrue(merged.covers(ItemKind.Chore, "hab-1", june(5)))
    }

    // ── covers over a list ───────────────────────────────────────────────────────────────────

    @Test
    fun coversIsFalseForAnUnknownDefinitionAndForAnEmptyList() {
        val ranges = listOf(coverage(june(10), june(20)))
        assertFalse(ranges.covers(ItemKind.Habit, "hab-nope", june(15)))
        assertFalse(emptyList<OccurrenceCoverage>().covers(ItemKind.Habit, "hab-1", june(15)))
    }
}
