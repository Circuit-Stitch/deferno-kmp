package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Contract for the two-part reading [readTodayOccurrence] produces (ADR-0053 decision 4) — *does
 * anything fire today* ([DayFiring]) and *how did today go* ([OccurrenceState]) — and for the
 * [factDateFor] arithmetic a caller needs between them.
 *
 * Three distinctions are the reason the types are shaped this way, and each is pinned below:
 *
 * - **[DayFiring.Unavailable] is not [DayFiring.NotFiring].** A grid this device cannot reproduce is
 *   not a grid that says nothing fires. Rendering "not scheduled today" for a rule we merely failed to
 *   expand states a fact we do not have — the same absent-vs-empty line [Expansion] draws, carried one
 *   layer up so a View cannot lose it.
 * - **A cancelled firing still [DayFiring.Fires].** The slot existed and was called off, which is a
 *   different statement from the rule never having fired; only [TodayOccurrence.isDue] collapses them.
 * - **The two halves are orthogonal.** The backend says so about its own field — `today_occurrence`
 *   *"always describes today's date for this item; it does not mean the item is scheduled to fire
 *   today"* — so a day with no firing still carries a state reading, and vice versa.
 *
 * `today` is the repo anchor, pinned like every other reading test: a value that is a function of
 * today cannot be captured, neither in a column nor in an assertion. 15 June 2026 is a **Monday**,
 * which is what the weekday-shaped fixtures below turn on.
 */
class TodayOccurrenceReadingTest {

    private val today = LocalDate(2026, 6, 15)
    private val la = "America/Los_Angeles"

    /** Anchored a fortnight before `today`, at 09:00 — no DST transition anywhere in this window. */
    private val anchor = LocalDateTime.parse("2026-06-01T09:00:00")
    private val daily = Recurrence(Cadence.Daily)
    private val series = SeriesInputs(anchorLocal = anchor, tzid = la)

    private fun fact(
        resolution: OccurrenceResolution,
        date: LocalDate = today,
    ) = OccurrenceFact(
        kind = ItemKind.Chore,
        definitionId = "chore-1",
        date = date,
        resolution = resolution,
        doneAt = Instant.parse("2026-06-15T09:00:00Z"),
        completeBy = Instant.parse("2026-06-15T23:00:00Z"),
    )

    private fun read(
        recurrence: Recurrence? = daily,
        series: SeriesInputs? = this.series,
        definitionState: DefinitionState? = DefinitionState.Active,
        fact: OccurrenceFact? = null,
        covered: Boolean = true,
    ) = readTodayOccurrence(recurrence, series, definitionState, fact, covered, today)

    // ── dayFiring: the two ways a grid can be missing ────────────────────────────────────────

    /**
     * No rule at all — a Task, or a recurring row whose rule did not survive the wire. There is nothing
     * to expand, so the honest answer is "cannot say", not "nothing fires".
     */
    @Test
    fun aRowWithNoRuleHasNoGridToRead() {
        assertEquals(DayFiring.Unavailable, dayFiring(null, series, today))
    }

    /**
     * The **elision**, and the distinction this whole type exists for. The backend omits the `series`
     * block when no series row backs the item rather than repairing one on read, so `null` means "this
     * device cannot reproduce that grid" — never "that grid has no exclusions". A reading that
     * collapsed it to [DayFiring.NotFiring] would tell someone their daily habit is not scheduled today.
     */
    @Test
    fun anElidedSeriesBlockIsUnavailableAndEmphaticallyNotNotFiring() {
        val elided = dayFiring(daily, series = null, date = today)
        assertEquals(DayFiring.Unavailable, elided)
        assertNotEquals<DayFiring>(DayFiring.NotFiring, elided)
    }

    /**
     * A `Custom` rule is a raw RFC 5545 string the expander deliberately refuses — expanding it would
     * mean writing a general RFC 5545 parser and reproducing the server's own bound-dropping bug (see
     * [ExpansionRefusal.CustomCadence]). The refusal is asserted alongside the reading so the linkage
     * between the two is visible: the day is Unavailable *because* the expander said so, not by
     * coincidence.
     */
    @Test
    fun aCustomRuleRefusesTheGridRatherThanReadingAsNotFiring() {
        val custom = Recurrence(Cadence.Custom("FREQ=HOURLY"))
        val expansion = expandOccurrenceGrid(custom, series, today, today)
        assertEquals(ExpansionRefusal.CustomCadence, assertIs<Expansion.NotExpandable>(expansion).reason)

        val firing = dayFiring(custom, series, today)
        assertEquals(DayFiring.Unavailable, firing)
        assertNotEquals<DayFiring>(DayFiring.NotFiring, firing)
    }

    // ── dayFiring: a grid that was actually reproduced ───────────────────────────────────────

    @Test
    fun aDayTheGridHitsFires() {
        val firing = assertIs<DayFiring.Fires>(dayFiring(daily, series, today))
        assertEquals(today, firing.firing.date)
        assertEquals(today, firing.firing.slotDate)
    }

    /**
     * The other side of the same rule. `today` is a Monday and this fires on Wednesdays only, so the
     * grid was genuinely reproduced and genuinely puts nothing on the day — which is a fact, unlike
     * the two refusals above.
     */
    @Test
    fun aDayTheGridMissesIsNotFiring() {
        val wednesdays = Recurrence(Cadence.Weekly(listOf("Wed")))
        assertEquals(DayFiring.NotFiring, dayFiring(wednesdays, series, today))
        // The neighbouring Wednesday is in the same grid — the miss is the day, not the rule.
        assertIs<DayFiring.Fires>(dayFiring(wednesdays, series, LocalDate(2026, 6, 17)))
    }

    /**
     * A cancelled firing arrives as [DayFiring.Fires], flagged — `expand_series` returns it that way and
     * a caller needs to know the slot existed. Only [TodayOccurrence.isDue] draws the "nothing to do"
     * conclusion, and it is the caller's to phrase: "cancelled today" and "not scheduled today" are
     * different sentences.
     */
    @Test
    fun aCancelledFiringStillFiresButIsNotDue() {
        val cancelled = series.copy(
            overrides = listOf(SeriesOverride(LocalDateTime.parse("2026-06-15T09:00:00"), isCancelled = true)),
        )
        val firing = assertIs<DayFiring.Fires>(dayFiring(daily, cancelled, today))
        assertTrue(firing.firing.isCancelled)
        assertNotEquals<DayFiring>(DayFiring.NotFiring, firing)

        assertFalse(read(series = cancelled).isDue)
        // The same day without the cancellation is due — the flag is the only difference.
        assertTrue(read().isDue)
    }

    @Test
    fun neitherAbsentGridReadsAsDue() {
        // `isDue` is a positive claim, so both refusals fall on the not-due side — but they do so
        // without ever having been turned into "nothing fires".
        assertFalse(read(series = null).isDue)
        assertFalse(read(recurrence = null).isDue)
        assertFalse(read(recurrence = Recurrence(Cadence.Weekly(listOf("Wed")))).isDue)
    }

    // ── factDateFor: the silent-miss guard ───────────────────────────────────────────────────

    /**
     * The one case where the render day and the identity day differ, and the reason [factDateFor]
     * exists at all. A rescheduled instance keeps its identity at the slot it moved *from* — that is
     * the date `OccurrenceTargets.of` and the fact table key on — so a caller querying the render day
     * would find no row and report a completed-but-moved firing as unresolved. A silent miss, not an
     * error.
     */
    @Test
    fun aRescheduledFiringIsLookedUpUnderTheSlotItMovedFrom() {
        // Sundays only, anchored on Sunday 14 June, with that firing moved onto Monday 15 June.
        val moved = SeriesInputs(
            anchorLocal = LocalDateTime.parse("2026-06-14T09:00:00"),
            tzid = la,
            overrides = listOf(
                SeriesOverride(
                    recurrenceId = LocalDateTime.parse("2026-06-14T09:00:00"),
                    movedToLocal = LocalDateTime.parse("2026-06-15T14:00:00"),
                ),
            ),
        )
        val sundays = Recurrence(Cadence.Weekly(listOf("Sun")))
        val firing = assertIs<DayFiring.Fires>(dayFiring(sundays, moved, today))

        // It RENDERS on today and is IDENTIFIED by yesterday — the two the guard keeps apart.
        assertEquals(today, firing.firing.date)
        assertEquals(LocalDate(2026, 6, 14), firing.firing.slotDate)
        assertEquals(LocalDate(2026, 6, 14), firing.factDateFor(today))
    }

    /**
     * The same contract at a distance the old ±1-day expansion window could not see, which is what made
     * that window wrong rather than merely narrow.
     *
     * [expandOccurrenceGrid] filters its window on the **slot**, by design — a firing moved out of the
     * window still comes back. So asking for slots `[today-1, today+1]` and then matching on the
     * *rendered* date cannot find a firing moved onto today from further out: the slot was never
     * generated. Today read `NotFiring` — a confident "nothing is scheduled today" on the exact day the
     * user moved it to — and [factDateFor] never saw the firing whose fact it exists to relocate, so a
     * completed occurrence also read as unresolved. A reschedule has no bounded distance, so the window
     * is derived from `overrides` instead of from a constant.
     */
    @Test
    fun aFiringMovedOntoTodayFromWellOutsideTheWindowIsStillFound() {
        // Sundays, anchored 3 May. The slot on 7 June — EIGHT days before today — was moved onto today.
        val movedFar = SeriesInputs(
            anchorLocal = LocalDateTime.parse("2026-05-03T09:00:00"),
            tzid = la,
            overrides = listOf(
                SeriesOverride(
                    recurrenceId = LocalDateTime.parse("2026-06-07T09:00:00"),
                    movedToLocal = LocalDateTime.parse("2026-06-15T14:00:00"),
                ),
            ),
        )
        val sundays = Recurrence(Cadence.Weekly(listOf("Sun")))
        val firing = assertIs<DayFiring.Fires>(dayFiring(sundays, movedFar, today))

        assertEquals(today, firing.firing.date, "it renders on today")
        assertEquals(LocalDate(2026, 6, 7), firing.firing.slotDate, "and is identified eight days back")
        assertEquals(LocalDate(2026, 6, 7), firing.factDateFor(today), "which is where its fact lives")
        assertTrue(read(recurrence = sundays, series = movedFar).isDue)
    }

    /**
     * The other direction, unchanged: a slot IN the window that was moved AWAY does not fire today. The
     * widened window must not turn every nearby slot into a hit.
     */
    @Test
    fun aFiringMovedAwayFromTodayDoesNotFireToday() {
        val movedAway = SeriesInputs(
            anchorLocal = LocalDateTime.parse("2026-06-15T09:00:00"),
            tzid = la,
            overrides = listOf(
                SeriesOverride(
                    recurrenceId = LocalDateTime.parse("2026-06-15T09:00:00"),
                    movedToLocal = LocalDateTime.parse("2026-06-29T14:00:00"),
                ),
            ),
        )
        val mondays = Recurrence(Cadence.Weekly(listOf("Mon")))
        assertEquals(DayFiring.NotFiring, dayFiring(mondays, movedAway, today))
    }

    @Test
    fun anUnmovedFiringIsLookedUpUnderTheDayItself() {
        assertEquals(today, dayFiring(daily, series, today).factDateFor(today))
    }

    @Test
    fun aDayWithNoFiringAtAllIsLookedUpUnderTheDayItself() {
        // Both absences answer with the plain date: there is no slot to have moved, so the arithmetic
        // is a no-op rather than a special case the caller has to guard.
        assertEquals(today, DayFiring.NotFiring.factDateFor(today))
        assertEquals(today, DayFiring.Unavailable.factDateFor(today))
    }

    // ── readTodayOccurrence: the state half ──────────────────────────────────────────────────

    /**
     * Outside [[Occurrence coverage]] the absence of a record is *ignorance*, not evidence — the arm
     * ADR-0053 was written to close. It holds regardless of what the grid says, because coverage is a
     * statement about what this device synced and the grid is a statement about the rule.
     */
    @Test
    fun anUncoveredDayIsUnknownWhateverTheGridSays() {
        assertEquals(OccurrenceState.Unknown, read(covered = false).state)
        assertEquals(OccurrenceState.Unknown, read(covered = false, series = null).state)
    }

    /**
     * Inside coverage with no record, today itself is Scheduled — the day is not over. The boundary is
     * `date >= today`, and `readTodayOccurrence` passes `today` for both, so today is always on the
     * Scheduled side and this reading can never age into Missed while it is being looked at.
     */
    @Test
    fun aCoveredTodayWithNoRecordIsScheduledNeverMissed() {
        assertEquals(OccurrenceState.Scheduled, read().state)
    }

    @Test
    fun aStoredFactIsReportedAsItsOwnResolution() {
        assertEquals(OccurrenceState.InProgress, read(fact = fact(OccurrenceResolution.InProgress)).state)
        assertEquals(OccurrenceState.DoneOnTime, read(fact = fact(OccurrenceResolution.DoneOnTime)).state)
        assertEquals(OccurrenceState.DoneLate, read(fact = fact(OccurrenceResolution.DoneLate)).state)
        assertEquals(OccurrenceState.Skipped, read(fact = fact(OccurrenceResolution.Skipped)).state)
        // And a fact outranks the bookkeeping: holding a record beats not having swept the range.
        assertEquals(
            OccurrenceState.DoneOnTime,
            read(fact = fact(OccurrenceResolution.DoneOnTime), covered = false).state,
        )
    }

    /**
     * Without the definition's light switch there is no way to tell a shelved series' history from a
     * live one's neglect, so the reading says so. This is also the arm that keeps a Task — which
     * resolves to no definition state at all — from reading Missed.
     */
    @Test
    fun anUncachedDefinitionStateIsUnknown() {
        assertEquals(OccurrenceState.Unknown, read(definitionState = null).state)
    }

    // ── The two halves are independent ───────────────────────────────────────────────────────

    /**
     * The conflation this type exists to prevent: `today_occurrence` "always describes today's date for
     * this item; it does not mean the item is scheduled to fire today". A day the grid misses still
     * carries a full state reading, and an unreproducible grid does not blank the state either — a
     * surface that rendered one and called it the other would report the series where the user asked
     * about the day.
     */
    @Test
    fun theStateIsReadEvenOnADayNothingFires() {
        val wednesdays = Recurrence(Cadence.Weekly(listOf("Wed")))
        assertEquals(
            TodayOccurrence(DayFiring.NotFiring, OccurrenceState.Scheduled),
            read(recurrence = wednesdays),
        )
        // A stored resolution on an unreproducible grid: the state stands AND is marked as stored, which
        // is what lets a renderer show it instead of the "not available" line.
        assertEquals(
            TodayOccurrence(DayFiring.Unavailable, OccurrenceState.DoneOnTime, isStoredResolution = true),
            read(series = null, fact = fact(OccurrenceResolution.DoneOnTime)),
        )
    }

    /**
     * The provenance split, and the reason it is a field rather than something a renderer infers.
     *
     * `Scheduled` arrives two ways that mean opposite things to a TODAY cell: derived (the day is
     * covered, nothing is recorded, and it has not passed) and stored (a `?scope=this` reschedule really
     * did write a `scheduled` row). Only the second is a fact this device holds. A renderer that guessed
     * would pick the confident reading and announce "Scheduled" for a grid nobody could expand — the
     * mirror image of the "Not scheduled today" lie [DayFiring] exists to prevent.
     */
    @Test
    fun aDerivedScheduledIsDistinguishableFromAStoredOne() {
        val derived = read(series = null, fact = null, covered = true)
        assertEquals(OccurrenceState.Scheduled, derived.state)
        assertFalse(derived.isStoredResolution, "covered-with-no-record is derived, not a stored fact")
        assertFalse(derived.isStateKnown, "and with no grid either, the day's state is simply not known")

        val stored = read(series = null, fact = fact(OccurrenceResolution.Scheduled), covered = true)
        assertEquals(OccurrenceState.Scheduled, stored.state, "identical value…")
        assertTrue(stored.isStoredResolution, "…opposite provenance")
        assertTrue(stored.isStateKnown)
    }

    /** A reproducible grid always knows the day's state, fact or no fact — there is nothing to hedge. */
    @Test
    fun aReproducibleGridAlwaysKnowsTheDaysState() {
        val wednesdays = Recurrence(Cadence.Weekly(listOf("Wed")))
        assertTrue(read(recurrence = wednesdays, covered = true).isStateKnown)
        assertTrue(read(recurrence = wednesdays, covered = false).isStateKnown)
    }

    /** The zero value: no grid, no fact, no coverage — what an unopened definition reads as. */
    @Test
    fun theUnknownReadingIsBothHalvesAbsent() {
        assertEquals(DayFiring.Unavailable, TodayOccurrence.Unknown.firing)
        assertEquals(OccurrenceState.Unknown, TodayOccurrence.Unknown.state)
        assertFalse(TodayOccurrence.Unknown.isDue)
        // And it is exactly what the reading produces from nothing at all.
        assertEquals(
            TodayOccurrence.Unknown,
            readTodayOccurrence(null, null, null, null, covered = false, today = today),
        )
    }

    @Test
    fun theReadingIsPureAndRepeatable() {
        // No clock read anywhere on the path — the same inputs give the same answer forever, which is
        // the property that makes it safe to derive at render time and never persist.
        assertEquals(read(), read())
        assertEquals(dayFiring(daily, series, today), dayFiring(daily, series, today))
    }
}
