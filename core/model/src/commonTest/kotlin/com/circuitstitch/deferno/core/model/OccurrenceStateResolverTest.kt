package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Contract for [resolveOccurrenceState] — the render-time reading ADR-0053 decision 4 turns on, and
 * the single function this whole slice exists to make possible offline.
 *
 * Every case pins `today` explicitly. A test that reached for a clock would pass today and rot
 * tomorrow, which is the exact defect the reading exists to remove: a value that is a function of
 * today cannot be captured, neither in a column nor in an assertion.
 *
 * **No case here pins the client to the server's answer for the Scheduled-vs-Missed split**, and that
 * is deliberate. The backend cuts that split on `Utc::now().date_naive()`
 * (`backend/src/handlers/occurrences.rs:465`, `:488`) while computing the same row's `complete_by` in
 * the user's zone (`:500-509`), so for anyone west of UTC it contradicts itself for the last hours of
 * each day. `today` here is the *user's local* today. ADR-0053 decision 5 puts that fix in Rust; the
 * client does not fork a second specification, and it does not enshrine the bug either.
 */
class OccurrenceStateResolverTest {

    private val today = LocalDate(2026, 6, 15)
    private val yesterday = LocalDate(2026, 6, 14)
    private val tomorrow = LocalDate(2026, 6, 16)

    private fun fact(
        resolution: OccurrenceResolution,
        date: LocalDate = yesterday,
    ) = OccurrenceFact(
        kind = ItemKind.Chore,
        definitionId = "chore-1",
        date = date,
        resolution = resolution,
        doneAt = Instant.parse("2026-06-14T09:00:00Z"),
        completeBy = Instant.parse("2026-06-14T23:00:00Z"),
    )

    private fun resolve(
        fact: OccurrenceFact? = null,
        covered: Boolean = true,
        definitionState: DefinitionState? = DefinitionState.Active,
        date: LocalDate = yesterday,
    ) = resolveOccurrenceState(fact, covered, definitionState, date, today)

    // ── Arm 1: a stored fact wins outright ───────────────────────────────────────────────────

    /**
     * The first arm is 1:1 and nothing is re-litigated on top of it — including the punctuality
     * split, which was decided at write time against the deadline the firing carried then, not
     * against the definition's cursor as it stands now.
     */
    @Test
    fun aStoredFactResolvesToItsOwnResolutionUnchanged() {
        assertEquals(OccurrenceState.Scheduled, resolve(fact(OccurrenceResolution.Scheduled)))
        assertEquals(OccurrenceState.InProgress, resolve(fact(OccurrenceResolution.InProgress)))
        assertEquals(OccurrenceState.DoneOnTime, resolve(fact(OccurrenceResolution.DoneOnTime)))
        assertEquals(OccurrenceState.DoneLate, resolve(fact(OccurrenceResolution.DoneLate)))
        assertEquals(OccurrenceState.Skipped, resolve(fact(OccurrenceResolution.Skipped)))
    }

    /**
     * A fact outranks both of the reasons the later arms would say Unknown. Holding a record for a
     * day is stronger evidence than the bookkeeping that says whether the day was swept, and a
     * definition that has fallen out of cache does not un-happen its history.
     */
    @Test
    fun aStoredFactWinsEvenWithoutCoverageOrACachedDefinition() {
        assertEquals(
            OccurrenceState.DoneOnTime,
            resolve(fact(OccurrenceResolution.DoneOnTime), covered = false, definitionState = null),
        )
    }

    /**
     * A stored `Scheduled` on a *past* day stays Scheduled rather than ageing into Missed. It is a
     * fact — an event row genuinely stores that variant — and the Missed derivation applies only
     * where there is no record at all.
     */
    @Test
    fun aStoredScheduledOnAPastDayIsNotAgedIntoMissed() {
        val eventFact = OccurrenceFact(
            kind = ItemKind.Event,
            definitionId = "event-1",
            date = yesterday,
            resolution = OccurrenceResolution.Scheduled,
        )
        assertEquals(OccurrenceState.Scheduled, resolve(eventFact))
    }

    // ── Arm 2: no fact, outside coverage → Unknown ───────────────────────────────────────────

    /**
     * The arm the whole epic exists for: this device has never synced that date, so the absence of a
     * record is *ignorance*, not evidence. Saying Missed here is the defect ADR-0053 was written to
     * close, and the Active definition + past date below is exactly the shape that would trigger it.
     */
    @Test
    fun aPastDayOutsideCoverageIsUnknownNotMissed() {
        assertEquals(OccurrenceState.Unknown, resolve(covered = false, date = yesterday))
    }

    @Test
    fun aFutureDayOutsideCoverageIsAlsoUnknown() {
        assertEquals(OccurrenceState.Unknown, resolve(covered = false, date = tomorrow))
    }

    // ── Arm 3: no fact, no cached definition → Unknown ───────────────────────────────────────

    /**
     * Same rule as coverage, one axis over: without the definition's light switch there is no way to
     * tell a shelved series' history from a live one's neglect, so the reading says so. This is the
     * arm that keeps a Task id — which resolves to no definition state at all — from reading Missed
     * on every past day.
     */
    @Test
    fun anUncachedDefinitionIsUnknownNeverMissed() {
        assertEquals(OccurrenceState.Unknown, resolve(definitionState = null, date = yesterday))
        assertEquals(OccurrenceState.Unknown, resolve(definitionState = null, date = tomorrow))
    }

    // ── Arm 4: no fact, inside coverage, today or later → Scheduled ──────────────────────────

    /** Nothing has happened yet because nothing was due to happen yet. */
    @Test
    fun aFutureDayInsideCoverageIsScheduled() {
        assertEquals(OccurrenceState.Scheduled, resolve(date = tomorrow))
    }

    /**
     * The boundary is `date >= today`, so **today itself is Scheduled**, not Missed. The day is not
     * over; a reading that called it missed at 00:01 would be both wrong and unkind.
     */
    @Test
    fun todayItselfIsScheduledNotMissed() {
        assertEquals(OccurrenceState.Scheduled, resolve(date = today))
    }

    /** A definition that is not Active still reads Scheduled on a future day — the split is past-only. */
    @Test
    fun aFutureDayIsScheduledWhateverTheDefinitionState() {
        assertEquals(OccurrenceState.Scheduled, resolve(definitionState = DefinitionState.Archived, date = tomorrow))
        assertEquals(OccurrenceState.Scheduled, resolve(definitionState = DefinitionState.InReview, date = tomorrow))
    }

    // ── Arm 5: no fact, inside coverage, past → Missed on Active, else Skipped ───────────────

    @Test
    fun aPastDayInsideCoverageOnAnActiveDefinitionIsMissed() {
        assertEquals(OccurrenceState.Missed, resolve(date = yesterday))
        assertEquals(OccurrenceState.Missed, resolve(date = LocalDate(2026, 1, 3)))
    }

    /**
     * The third arm no issue mentions, mirroring the backend's own
     * (`backend/src/handlers/occurrences.rs:488-495`): a past unrecorded day on a **non-Active**
     * definition is Skipped, not Missed. It matters because `archive_habit` leaves `complete_by`
     * untouched on archive, so a definition switched off in January would otherwise report every day
     * since as overdue — a shelved definition's empty days are history, not a reproach.
     */
    @Test
    fun aPastDayInsideCoverageOnAShelvedDefinitionIsSkippedNotMissed() {
        assertEquals(OccurrenceState.Skipped, resolve(definitionState = DefinitionState.Archived, date = yesterday))
    }

    /**
     * `InReview` is retained faithfully pending a backend clarification (ADR-0011) and is not Active,
     * so it takes the same arm as Archived. Pinned so a later reading of that wire value is a
     * deliberate change rather than an accident.
     */
    @Test
    fun aPastDayOnAnInReviewDefinitionTakesTheNonActiveArm() {
        assertEquals(OccurrenceState.Skipped, resolve(definitionState = DefinitionState.InReview, date = yesterday))
    }

    // ── toOccurrenceState — the widening ─────────────────────────────────────────────────────

    /**
     * The widening is total and lossless: every stored resolution has an identical-meaning reading,
     * and the two members that exist only as readings ([OccurrenceState.Missed],
     * [OccurrenceState.Unknown]) are exactly the ones no row can hold. Asserted over `entries` so a
     * sixth resolution cannot be added without an answer here.
     */
    @Test
    fun everyStoredResolutionWidensToTheSameNamedReading() {
        assertEquals(
            OccurrenceResolution.entries.map { it.name },
            OccurrenceResolution.entries.map { it.toOccurrenceState().name },
        )
    }

    @Test
    fun theTwoReadingOnlyMembersAreMissedAndUnknown() {
        val widened = OccurrenceResolution.entries.map { it.toOccurrenceState() }.toSet()
        assertEquals(
            setOf(OccurrenceState.Missed, OccurrenceState.Unknown),
            OccurrenceState.entries.toSet() - widened,
        )
    }
}
