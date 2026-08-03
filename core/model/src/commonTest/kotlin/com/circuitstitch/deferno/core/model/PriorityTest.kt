package com.circuitstitch.deferno.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Contract for [Priority] and the canonical ranked-view comparator [prioritySortKey] (#375) — the
 * client half of the server's `models::priority` (Deferno ADR 2026-06-29 "priority-model"). The
 * backend's own unit tests are mirrored here case-for-case: the *same* key must rank a list the same
 * way on either side, or a client-ranked surface would disagree with `$orderby=priority_rank`.
 */
class PriorityTest {

    private fun at(iso: String) = Instant.parse(iso)
    private val created = at("2026-01-01T00:00:00Z")

    @Test
    fun defaultIsNormal() {
        assertEquals(Priority.Normal, Priority.Default)
    }

    @Test
    fun hasTheThreeBucketConstantsInRankOrder() {
        // Declaration order IS the rank (the server's `#[repr(u8)] Fire = 0 … Backlog = 2`).
        assertEquals(listOf("Fire", "Normal", "Backlog"), Priority.entries.map { it.name })
    }

    @Test
    fun bucketRankOrdersFireAboveNormalAboveBacklog() {
        assertTrue(Priority.Fire.bucketRank < Priority.Normal.bucketRank)
        assertTrue(Priority.Normal.bucketRank < Priority.Backlog.bucketRank)
        assertEquals(0, Priority.Fire.bucketRank)
        assertEquals(1, Priority.Normal.bucketRank)
        assertEquals(2, Priority.Backlog.bucketRank)
    }

    // --- the comparator: lexicographic by bucket, then soonest relevant date ---

    @Test
    fun bucketDominatesTheDate() {
        // A Fire item with a far deadline still beats a Backlog item that is overdue.
        val fireFar = prioritySortKey(Priority.Fire, null, at("2026-12-01T00:00:00Z"), created)
        val backlogOverdue = prioritySortKey(Priority.Backlog, null, at("2026-01-02T00:00:00Z"), created)
        assertTrue(fireFar < backlogOverdue, "Fire must out-rank Backlog regardless of date")
    }

    @Test
    fun withinABucketSoonerSortsFirst() {
        val soon = prioritySortKey(Priority.Normal, null, at("2026-02-01T00:00:00Z"), created)
        val late = prioritySortKey(Priority.Normal, null, at("2026-06-01T00:00:00Z"), created)
        assertTrue(soon < late)
    }

    @Test
    fun softTargetDateDrivesWithinBucketUrgencyOverTheHardDeadline() {
        // Same far deadline on both; the near *target* is what surfaces the first one.
        val targetNear = prioritySortKey(
            Priority.Normal,
            at("2026-02-01T00:00:00Z"),
            at("2026-12-01T00:00:00Z"),
            created,
        )
        val targetFar = prioritySortKey(
            Priority.Normal,
            at("2026-11-01T00:00:00Z"),
            at("2026-12-01T00:00:00Z"),
            created,
        )
        assertTrue(targetNear < targetFar, "target_date must drive within-bucket urgency")
    }

    @Test
    fun undatedItemsSinkWithinTheirBucket() {
        val dated = prioritySortKey(Priority.Normal, null, at("2026-02-01T00:00:00Z"), created)
        val undated = prioritySortKey(Priority.Normal, null, null, created)
        assertTrue(dated < undated, "a dated item beats an undated one within a bucket")
    }

    @Test
    fun hardDeadlineThenCreatedBreakTies() {
        // Identical bucket + identical soonest-relevant date (both from target_date): the hard
        // deadline breaks the tie, and `date_created` breaks that.
        val target = at("2026-03-01T00:00:00Z")
        val earlierDeadline = prioritySortKey(Priority.Normal, target, at("2026-04-01T00:00:00Z"), created)
        val laterDeadline = prioritySortKey(Priority.Normal, target, at("2026-05-01T00:00:00Z"), created)
        assertTrue(earlierDeadline < laterDeadline)

        val older = prioritySortKey(Priority.Normal, target, null, at("2026-01-01T00:00:00Z"))
        val newer = prioritySortKey(Priority.Normal, target, null, at("2026-01-02T00:00:00Z"))
        assertTrue(older < newer)
    }

    @Test
    fun aBacklogItemStaysRankableRatherThanBeingHidden() {
        // Breakdown's "stressed" move lowers priority; the item must still sort, never vanish.
        val backlog = prioritySortKey(Priority.Backlog, null, null, created)
        val normal = prioritySortKey(Priority.Normal, null, null, created)
        assertTrue(normal < backlog)
        assertEquals(Priority.Backlog.bucketRank, backlog.bucketRank)
    }

    @Test
    fun sortingAListRanksFireFirstAndBacklogLast() {
        data class Row(val name: String, val p: Priority, val target: Instant?, val by: Instant?)

        val rows = listOf(
            Row("backlog-overdue", Priority.Backlog, null, at("2026-01-02T00:00:00Z")),
            Row("normal-undated", Priority.Normal, null, null),
            Row("normal-soon", Priority.Normal, null, at("2026-02-01T00:00:00Z")),
            Row("fire-far", Priority.Fire, null, at("2026-12-01T00:00:00Z")),
            Row("normal-targeted", Priority.Normal, at("2026-01-15T00:00:00Z"), at("2026-12-01T00:00:00Z")),
        )

        val ranked = rows.sortedBy { prioritySortKey(it.p, it.target, it.by, created) }.map { it.name }

        assertEquals(
            listOf("fire-far", "normal-targeted", "normal-soon", "normal-undated", "backlog-overdue"),
            ranked,
        )
    }
}
