package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The flush-time occurrence coalescer (#396) — the pure half, so every cell of the collapse truth table
 * in [coalesceOccurrences] is pinned by name on the ADR-0006 JVM-fast path. `OutboxProcessorTest` and
 * `OccurrenceOfflineToOnlineTest` cover the applied half (the deletes, the counter and the end-to-end
 * "one server write" acceptance).
 *
 * Every fixture builds its request from the **real** [OccurrenceMutation] rather than a hand-written
 * path, because the classifier reads the route shape: if a mark, clear or reschedule route ever moves,
 * this file must fail rather than quietly reclassify the intent.
 */
class OutboxCoalescingTest {

    private val t0 = Instant.parse("2026-06-07T12:00:00Z")
    private val day = LocalDate(2026, 6, 8)

    // --- fixtures ---------------------------------------------------------------------------------

    private fun mark(
        action: OccurrenceAction = OccurrenceAction.Complete,
        kind: ItemKind = ItemKind.Chore,
        id: String = "cho-1-item",
        on: LocalDate = day,
    ) = MarkOccurrence("ce-1", kind, id, on, action)

    private fun clear(kind: ItemKind = ItemKind.Chore, id: String = "cho-1-item", on: LocalDate = day) =
        ClearOccurrence("ce-1", kind, id, on)

    private fun reschedule(kind: ItemKind = ItemKind.Chore, id: String = "cho-1-item", on: LocalDate = day) =
        RescheduleOccurrence("ce-1", kind, id, on, LocalDate(2026, 6, 10))

    private fun row(seq: Long, mutation: OccurrenceMutation, failedAt: Instant? = null) =
        row(seq, mutation.target, mutation.toRequest(), failedAt)

    private fun row(seq: Long, target: String, request: OutboxRequest, failedAt: Instant? = null) = OutboxEntry(
        seq = seq,
        target = target,
        request = request,
        attempts = 0,
        nextAttemptAt = t0,
        createdAt = t0,
        failedAt = failedAt,
    )

    /** The four absolute set-state intents, named for the truth-table assertions. */
    private val absolutes = listOf(
        "mark(start)" to mark(OccurrenceAction.Start),
        "mark(complete)" to mark(OccurrenceAction.Complete),
        "mark(skip)" to mark(OccurrenceAction.Skip),
        "clear" to clear(),
    )

    // --- the truth table --------------------------------------------------------------------------

    @Test
    fun everyAbsolutePairOnOneKeyCollapsesToTheLater() {
        // The 4x4 top-left block: mark/clear x mark/clear. Both are absolute writes on one (item, date)
        // row, so the later one fully determines server state and the earlier is redundant.
        for ((earlierName, earlier) in absolutes) {
            for ((laterName, later) in absolutes) {
                val drops = coalesceOccurrences(listOf(row(1, earlier), row(2, later)))
                assertEquals(listOf(1L), drops, "$earlierName then $laterName must drop the earlier")
            }
        }
    }

    @Test
    fun noPairInvolvingARescheduleCollapses() {
        // The barrier row and column, plus the reschedule/reschedule cell: nine cells, all "keep both".
        // A reschedule keys on its ORIGIN date while moving the firing elsewhere, so collapsing across it
        // would depend on a backend implementation detail and lose the server-side Activity entry.
        for ((name, absolute) in absolutes) {
            assertTrue(
                coalesceOccurrences(listOf(row(1, absolute), row(2, reschedule()))).isEmpty(),
                "$name then reschedule must keep both",
            )
            assertTrue(
                coalesceOccurrences(listOf(row(1, reschedule()), row(2, absolute))).isEmpty(),
                "reschedule then $name must keep both",
            )
        }
        assertTrue(coalesceOccurrences(listOf(row(1, reschedule()), row(2, reschedule()))).isEmpty())
    }

    @Test
    fun theIssuesOwnCaseCollapsesThreeWritesToOne() {
        // #396 verbatim: offline mark -> clear -> mark on one firing. Two rows go; the survivor is the
        // LAST one, keeping its own seq — nothing is ever rewritten backward into an earlier slot.
        val drops = coalesceOccurrences(
            listOf(row(1, mark()), row(2, clear()), row(3, mark())),
        )
        assertEquals(listOf(1L, 2L), drops)
    }

    @Test
    fun aRescheduleSplitsTheRunSoEachSideCompactsIndependently() {
        val drops = coalesceOccurrences(
            listOf(
                row(1, mark(OccurrenceAction.Start)),
                row(2, mark(OccurrenceAction.Complete)),
                row(3, reschedule()),
                row(4, clear()),
                row(5, mark(OccurrenceAction.Complete)),
            ),
        )
        // Mark|Reschedule|Mark survives as three entries, not one: 2 and 5 are the two run survivors and
        // 3 is the barrier itself.
        assertEquals(listOf(1L, 4L), drops)
    }

    // --- key identity -----------------------------------------------------------------------------

    @Test
    fun aDifferentKindIdOrDateIsADifferentFiringAndNeverMerges() {
        val entries = listOf(
            row(1, mark(kind = ItemKind.Chore, id = "i-1", on = day)),
            row(2, mark(kind = ItemKind.Event, id = "i-1", on = day)), // different kind
            row(3, mark(kind = ItemKind.Chore, id = "i-2", on = day)), // different definition id
            row(4, mark(kind = ItemKind.Chore, id = "i-1", on = LocalDate(2026, 6, 9))), // different day
        )
        assertTrue(coalesceOccurrences(entries).isEmpty())
    }

    @Test
    fun twoHabitMarksOnDifferentDaysNeverMergeDespiteIdenticalPaths() {
        // The habit mark is the shape that would break a path-keyed coalescer: `POST habits/{id}/
        // occurrences` carries its date in the BODY, so two different firings render byte-different
        // bodies at an identical path. Keying on the outbox target (which carries the date) is what
        // keeps them apart.
        val monday = mark(kind = ItemKind.Habit, id = "hab-3-item", on = day)
        val tuesday = mark(kind = ItemKind.Habit, id = "hab-3-item", on = LocalDate(2026, 6, 9))
        assertEquals(monday.toRequest().path, tuesday.toRequest().path)

        assertTrue(coalesceOccurrences(listOf(row(1, monday), row(2, tuesday))).isEmpty())
        // …and the same day still collapses, so the guard above is discrimination, not paralysis.
        assertEquals(listOf(1L), coalesceOccurrences(listOf(row(1, monday), row(2, monday))))
    }

    // --- everything the pass must not touch --------------------------------------------------------

    @Test
    fun foreignTargetsAreNeitherDroppedNorBarriers() {
        // The outbox is mixed. A task edit, a plan reorder, a settings write, a create and a comment sit
        // between two marks of one firing: none may be dropped, and none may stop the two marks merging
        // — deleting somebody else's row from around them cannot move them.
        val foreign = listOf(
            "task:t-1" to OutboxRequest(OutboxMethod.Patch, listOf("tasks", "t-1"), """{"title":"x"}"""),
            "plan:2026-06-08:UTC" to OutboxRequest(OutboxMethod.Post, listOf("plan", "2026-06-08")),
            "settings" to OutboxRequest(OutboxMethod.Patch, listOf("settings"), "{}"),
            "create:Habit:client-1" to OutboxRequest(OutboxMethod.Post, listOf("habits"), "{}"),
            "comment:t-1:c-1" to OutboxRequest(OutboxMethod.Patch, listOf("items", "t-1", "comments", "c-1")),
        )
        val entries = buildList {
            add(row(1, mark()))
            foreign.forEachIndexed { index, (target, request) -> add(row(2L + index, target, request)) }
            add(row(2L + foreign.size, mark()))
        }
        assertEquals(listOf(1L), coalesceOccurrences(entries))
    }

    @Test
    fun aMalformedOccurrenceTargetIsIgnoredEntirely() {
        // `parse` is tolerant by design, so a row this build cannot decode is skipped: never dropped, and
        // never a barrier (it has no key to bound a run on).
        val undecodable = row(2, "occurrence:Sprint:s-1:2026-06-08", mark().toRequest())
        assertEquals(
            listOf(1L),
            coalesceOccurrences(listOf(row(1, mark()), undecodable, row(3, mark()))),
        )
    }

    @Test
    fun anUnknownOccurrenceVerbFailsClosedAsABarrier() {
        // A future occurrence route this build does not know must not be collapsed into (it might not be
        // an absolute write) nor collapsed across (it might not be commutative with its neighbours).
        val unknown = row(
            2,
            mark().target,
            OutboxRequest(OutboxMethod.Post, listOf("chores", "cho-1-item", "occurrences", "2026-06-08", "snooze")),
        )
        assertEquals(CollapseRole.Barrier, collapseRoleOf(unknown.request))
        assertTrue(coalesceOccurrences(listOf(row(1, mark()), unknown, row(3, mark()))).isEmpty())
    }

    // --- dead letters -----------------------------------------------------------------------------

    @Test
    fun aDeadLetteredRowIsNeitherDroppedNorABarrier() {
        val dead = t0

        // (a) Dead-lettered rows are preserved by design (they still protect the optimistic local row),
        //     so a live successor must not delete one.
        assertTrue(coalesceOccurrences(listOf(row(1, mark(), failedAt = dead), row(2, mark()))).isEmpty())

        // (b) …nor may one be treated as a live predecessor: the two LIVE marks collapse around it.
        assertEquals(
            listOf(2L),
            coalesceOccurrences(listOf(row(1, mark(), failedAt = dead), row(2, mark()), row(3, mark()))),
        )

        // (c) A dead-lettered barrier can never reach the server, so it cannot separate two writes that
        //     can: the marks either side still collapse.
        assertEquals(
            listOf(1L),
            coalesceOccurrences(listOf(row(1, mark()), row(2, reschedule(), failedAt = dead), row(3, mark()))),
        )
    }

    // --- shape of the result ----------------------------------------------------------------------

    @Test
    fun theResultIsAscendingEvenWhenKeysInterleave() {
        // Walking in seq order emits key A's victim after key B's, so the raw order is not sorted; the
        // caller deletes and logs in queue order, so the function sorts.
        val a = "a-item"
        val b = "b-item"
        val drops = coalesceOccurrences(
            listOf(
                row(1, mark(id = a)),
                row(2, mark(id = b)),
                row(3, mark(id = b)), // supersedes 2
                row(4, mark(id = a)), // supersedes 1
            ),
        )
        assertEquals(listOf(1L, 2L), drops)
    }

    @Test
    fun aQueueWithNothingToCollapseYieldsNothing() {
        assertTrue(coalesceOccurrences(emptyList()).isEmpty())
        assertTrue(coalesceOccurrences(listOf(row(1, mark()))).isEmpty())
        assertTrue(coalesceOccurrences(listOf(row(1, reschedule()))).isEmpty())
    }

    @Test
    fun theSurvivorOfEveryRunIsItsLatestEntry() {
        // The invariant the FIFO argument rests on: nothing ever moves earlier, so the highest seq of a
        // collapsed run is never a drop.
        val entries = (1L..6L).map { row(it, mark()) }
        val drops = coalesceOccurrences(entries)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), drops)
        assertTrue(6L !in drops)
    }

    // --- the classifier ---------------------------------------------------------------------------

    @Test
    fun everyMarkAndClearRouteClassifiesAsAbsolute() {
        // The habit mark ends at `occurrences`; the chore PUT and event POST end at `{date}`; clear ends
        // at `clear`. Three different tail shapes, one role.
        assertEquals(CollapseRole.Absolute, collapseRoleOf(mark(kind = ItemKind.Habit).toRequest()))
        assertEquals(CollapseRole.Absolute, collapseRoleOf(mark(kind = ItemKind.Chore).toRequest()))
        assertEquals(CollapseRole.Absolute, collapseRoleOf(mark(kind = ItemKind.Event).toRequest()))
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            assertEquals(CollapseRole.Absolute, collapseRoleOf(clear(kind).toRequest()), "clear for $kind")
        }
    }

    @Test
    fun everyRescheduleRouteClassifiesAsABarrier() {
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            assertEquals(CollapseRole.Barrier, collapseRoleOf(reschedule(kind).toRequest()), "reschedule for $kind")
        }
    }

    @Test
    fun anUnrecognisableRouteClassifiesAsABarrier() {
        // Total over any path at all, including the degenerate ones — the classifier is reached from a
        // mixed queue and may never throw.
        assertEquals(CollapseRole.Barrier, collapseRoleOf(OutboxRequest(OutboxMethod.Post, emptyList())))
        assertEquals(CollapseRole.Barrier, collapseRoleOf(OutboxRequest(OutboxMethod.Post, listOf("chores"))))
        assertEquals(
            CollapseRole.Barrier,
            collapseRoleOf(OutboxRequest(OutboxMethod.Patch, listOf("tasks", "t-1"))),
        )
    }
}
