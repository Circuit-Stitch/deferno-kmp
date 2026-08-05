package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The [nextDeadlineAfter] branch — every arm of the backend's `next_chain_complete_by` selection, plus
 * the two behaviours that look like bugs and are deliberately reproduced because parity is normative
 * (ADR-0053 decision 5).
 */
class NextDeadlineTest {

    private val la = TimeZone.of("America/Los_Angeles")

    /** 2026-04-10 at 18:00 local (LA is UTC-7 in April). */
    private val doneAt = Instant.parse("2026-04-11T01:00:00Z")

    private fun next(
        mode: CadenceMode,
        cadence: Cadence,
        bound: RecurrenceBound = RecurrenceBound.Never,
        at: Instant = doneAt,
        zone: TimeZone = la,
    ) = nextDeadlineAfter(mode, Recurrence(cadence, bound), at, zone)

    // ── Arm selection ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aRollingDailyChoreReAnchorsOffTheCompletion() {
        assertEquals(
            NextDeadline.RollingTo(LocalDate.parse("2026-04-11")),
            next(CadenceMode.Rolling, Cadence.Daily),
        )
    }

    @Test
    fun aRollingStrideAddsItsOwnIntervalNotOneDay() {
        assertEquals(
            NextDeadline.RollingTo(LocalDate.parse("2026-05-10")),
            next(CadenceMode.Rolling, Cadence.EveryNDays(30)),
        )
    }

    @Test
    fun aFixedChoreAlwaysFallsThroughToTheGridEvenOnAStrideCadence() {
        assertEquals(NextDeadline.FromGrid, next(CadenceMode.Fixed, Cadence.Daily))
        assertEquals(NextDeadline.FromGrid, next(CadenceMode.Fixed, Cadence.EveryNDays(30)))
    }

    @Test
    fun aRollingChoreWithoutADayStrideStillConsultsTheGrid() {
        // The mode is rolling, but a weekly rule's effective interval varies week to week and
        // monthly/yearly depend on calendar boundaries, so there is nothing to add.
        assertEquals(NextDeadline.FromGrid, next(CadenceMode.Rolling, Cadence.Weekly(listOf("Mon", "Wed"))))
        assertEquals(NextDeadline.FromGrid, next(CadenceMode.Rolling, Cadence.Monthly(1, MonthlyAnchor.DayOfMonth(15))))
        assertEquals(NextDeadline.FromGrid, next(CadenceMode.Rolling, Cadence.Yearly(1, 6, 14)))
        assertEquals(NextDeadline.FromGrid, next(CadenceMode.Rolling, Cadence.Custom("FREQ=HOURLY")))
    }

    @Test
    fun anUnmodelledModeIsNotTreatedAsRolling() {
        // Guessing rolling for a mode we cannot read would silently change how the chore schedules.
        assertEquals(NextDeadline.FromGrid, next(CadenceMode.Unmodelled("drifting"), Cadence.Daily))
    }

    // ── The UNTIL guard (#429) ────────────────────────────────────────────────────────────────────

    @Test
    fun aRollingChoreDoesNotWalkPastItsOwnBound() {
        assertEquals(
            NextDeadline.Ended,
            next(CadenceMode.Rolling, Cadence.Daily, RecurrenceBound.OnDate(LocalDate.parse("2026-04-10"))),
        )
    }

    @Test
    fun aCandidateLandingExactlyOnTheBoundStillSchedules() {
        // The comparison is strictly `>`. An off-by-one here turns the last legitimate firing into a
        // cleared cursor.
        assertEquals(
            NextDeadline.RollingTo(LocalDate.parse("2026-04-11")),
            next(CadenceMode.Rolling, Cadence.Daily, RecurrenceBound.OnDate(LocalDate.parse("2026-04-11"))),
        )
    }

    @Test
    fun aCountBoundDoesNotStopARollingChore() {
        // NOT a bug here: the backend reads the bound through `until_local_date()`, which answers only
        // for `OnDate`, so a rolling `daily; COUNT=3` chore re-anchors forever server-side. Adding the
        // sensible-looking guard would make this client disagree with the server about when a series
        // ends. If that is wrong, it is wrong in Rust — this test exists to make the choice deliberate.
        assertEquals(
            NextDeadline.RollingTo(LocalDate.parse("2026-04-11")),
            next(CadenceMode.Rolling, Cadence.Daily, RecurrenceBound.AfterCount(3)),
        )
    }

    // ── The zone is load-bearing ──────────────────────────────────────────────────────────────────

    @Test
    fun theCandidateDateIsReadInTheSuppliedZoneNotUtc() {
        // 2026-04-11T01:00Z is still 10 April in Los Angeles but already 11 April in UTC. Deriving the
        // completion's date in the wrong zone shifts every rolling chore by a day for anyone west of
        // Greenwich — the same class of defect as the backend's own UTC-based Missed split.
        assertEquals(
            NextDeadline.RollingTo(LocalDate.parse("2026-04-11")),
            next(CadenceMode.Rolling, Cadence.Daily, zone = la),
        )
        assertEquals(
            NextDeadline.RollingTo(LocalDate.parse("2026-04-12")),
            next(CadenceMode.Rolling, Cadence.Daily, zone = TimeZone.UTC),
        )
    }

    // ── The stride gate ───────────────────────────────────────────────────────────────────────────

    @Test
    fun onlyDailyAndEveryNDaysHaveADayStride() {
        assertEquals(1, Cadence.Daily.intervalAsDays)
        assertEquals(3, Cadence.EveryNDays(3).intervalAsDays)
        assertNull(Cadence.Weekly(listOf("Mon")).intervalAsDays)
        assertNull(Cadence.Monthly(1, MonthlyAnchor.DayOfMonth(1)).intervalAsDays)
        assertNull(Cadence.Yearly(1, 1, 1).intervalAsDays)
        assertNull(Cadence.Custom("FREQ=HOURLY").intervalAsDays)
        assertNull(Cadence.Unmodelled("lunar").intervalAsDays)
    }

    @Test
    fun aStrideOfZeroOrLessHasNoIntervalAndSoFallsThroughToTheGrid() {
        // A chore re-anchoring to `done_at + 0 days` would be due again the instant it was finished,
        // forever. The Rust guards this with an explicit `n > 0`.
        assertNull(Cadence.EveryNDays(0).intervalAsDays)
        assertNull(Cadence.EveryNDays(-1).intervalAsDays)
        assertEquals(NextDeadline.FromGrid, next(CadenceMode.Rolling, Cadence.EveryNDays(0)))
    }

    @Test
    fun theBranchIsPureAndRepeatable() {
        assertEquals(next(CadenceMode.Rolling, Cadence.Daily), next(CadenceMode.Rolling, Cadence.Daily))
    }
}
