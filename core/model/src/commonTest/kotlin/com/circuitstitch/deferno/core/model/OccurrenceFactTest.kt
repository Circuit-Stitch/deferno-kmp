package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Contract for the stored half of a firing: [OccurrenceFact], the [OccurrenceResolution] vocabulary a
 * server row can actually hold, and the [completionResolution] punctuality split.
 *
 * The parity target is the Rust, which is normative (ADR-0053 decision 5). Where a case pins a
 * number, the backend anchor is named beside it.
 */
class OccurrenceFactTest {

    private val deadline = Instant.parse("2026-06-14T23:00:00Z")

    // ── completionResolution — the punctuality split ─────────────────────────────────────────

    @Test
    fun finishingBeforeTheDeadlineIsOnTime() {
        assertEquals(
            OccurrenceResolution.DoneOnTime,
            completionResolution(Instant.parse("2026-06-14T09:00:00Z"), deadline),
        )
    }

    /**
     * **The bound is inclusive.** `done_at == complete_by` is on time, mirroring the backend's
     * `decide_chore_done_status` (`backend/src/handlers/occurrences.rs:1164-1173`), which compares
     * `done_at <= complete_by`. An exclusive bound here would flip a firing finished on the stroke of
     * the deadline to Late — a one-instant disagreement with the server that would look like a bug in
     * the person's history rather than in ours.
     */
    @Test
    fun finishingExactlyOnTheDeadlineIsOnTimeNotLate() {
        assertEquals(OccurrenceResolution.DoneOnTime, completionResolution(deadline, deadline))
    }

    /** One second past the bound is Late — the inclusive bound above is inclusive of *exactly* itself. */
    @Test
    fun finishingAfterTheDeadlineIsLate() {
        assertEquals(OccurrenceResolution.DoneLate, completionResolution(deadline + 1.seconds, deadline))
        assertEquals(
            OccurrenceResolution.DoneLate,
            completionResolution(Instant.parse("2026-06-15T02:00:00Z"), deadline),
        )
    }

    /**
     * A firing with no recorded deadline cannot be late: with nothing to be late *against*, on time is
     * the only honest reading. This is the Habit shape — the wire row is `{ habit_id, date, done_at }`
     * with no deadline column at all (`backend/src/models/occurrence.rs:31-36`) — so it is the common
     * case, not an edge one.
     */
    @Test
    fun aFiringWithNoRecordedDeadlineIsOnTimeHoweverLateItLooks() {
        assertEquals(
            OccurrenceResolution.DoneOnTime,
            completionResolution(Instant.parse("2099-01-01T00:00:00Z"), null),
        )
    }

    // ── OccurrenceResolution — the stored vocabulary ─────────────────────────────────────────

    /**
     * Five members, not four: the stored vocabulary genuinely differs per kind and this is the union.
     * Chore stores four (`ChoreOccurrenceStatus`), Event stores five including a genuinely stored
     * `scheduled` (`OccurrenceStatus`, `backend/src/models/occurrence.rs:257-264`), and Habit stores
     * none at all. `Missed` is absent and always will be — it is derived, never recorded.
     */
    @Test
    fun theStoredVocabularyIsTheFiveMemberUnionAndExcludesMissed() {
        assertEquals(
            setOf("Scheduled", "InProgress", "DoneOnTime", "DoneLate", "Skipped"),
            OccurrenceResolution.entries.map { it.name }.toSet(),
        )
        assertFalse(OccurrenceResolution.entries.any { it.name == "Missed" })
        assertFalse(OccurrenceResolution.entries.any { it.name == "Unknown" })
    }

    @Test
    fun terminalResolutionsAreTheThreeWrittenOutcomes() {
        assertTrue(OccurrenceResolution.DoneOnTime.isTerminal)
        assertTrue(OccurrenceResolution.DoneLate.isTerminal)
        assertTrue(OccurrenceResolution.Skipped.isTerminal)
        assertFalse(OccurrenceResolution.Scheduled.isTerminal)
        assertFalse(OccurrenceResolution.InProgress.isTerminal)
    }

    // ── The fact's identity ──────────────────────────────────────────────────────────────────

    private fun fact(
        kind: ItemKind = ItemKind.Chore,
        definitionId: String = "def-1",
        date: LocalDate = LocalDate(2026, 6, 14),
    ) = OccurrenceFact(kind, definitionId, date, OccurrenceResolution.DoneOnTime)

    /**
     * The key is `(kind, definitionId, date)` — the identity the write path has always used, built by
     * `OccurrenceTargets.of`. All three parts are load-bearing: two kinds can hold the same
     * definition id, and one definition fires on many dates. Equality is what a store's upsert
     * collapses on, so pinning it here pins the table's grain.
     */
    @Test
    fun twoFactsAreTheSameFiringOnlyWhenKindDefinitionAndDateAllMatch() {
        assertEquals(fact(), fact())
        assertNotEquals(fact(), fact(kind = ItemKind.Event))
        assertNotEquals(fact(), fact(definitionId = "def-2"))
        assertNotEquals(fact(), fact(date = LocalDate(2026, 6, 15)))
    }

    /**
     * [OccurrenceFact.completeBy] is the deadline the firing carried **when it was resolved**, kept
     * because the punctuality split is a function of it and the definition's live `completeBy` is a
     * recurrence cursor that has since walked on. Reading the split off the stored pair reproduces the
     * server's answer with the server gone.
     */
    @Test
    fun aFactCarriesEnoughToReDeriveItsOwnPunctuality() {
        val late = OccurrenceFact(
            kind = ItemKind.Chore,
            definitionId = "def-1",
            date = LocalDate(2026, 6, 14),
            resolution = OccurrenceResolution.DoneLate,
            doneAt = Instant.parse("2026-06-15T02:00:00Z"),
            completeBy = deadline,
        )
        assertEquals(late.resolution, completionResolution(late.doneAt!!, late.completeBy))
    }

    /** Timestamps default to absent: a Scheduled or InProgress row records no completion. */
    @Test
    fun timestampsAreAbsentByDefault() {
        assertEquals(null, fact().doneAt)
        assertEquals(null, fact().completeBy)
    }
}
