package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Where a [Chore]'s deadline lands once a firing is **closed out** — the client port of the backend's
 * arm selection in `next_chain_complete_by` (`backend/src/handlers/occurrences.rs`).
 *
 * **An expander alone gets the default chore's next-due wrong**, which is the whole reason this exists
 * beside [expandOccurrenceGrid]. [CadenceMode.Rolling] is the backend's default, and on the two
 * commonest cadences it makes the next deadline a function of *when the user actually finished* rather
 * than of the rule — "This arm never consults the series — the interval IS the rule", in the Rust's own
 * words. A client that read next-due off the grid for every chore would be right about [CadenceMode.Fixed]
 * chores, right about rolling weekly/monthly/yearly ones, and wrong about the most common case there is.
 *
 * So this is a **branch, not a replacement**: it says which of the two rules applies, and the grid is
 * still the answer whenever it returns [FromGrid].
 */
sealed interface NextDeadline {

    /**
     * The rolling re-anchor: `done_at + n days`, and the grid is never consulted. Carries a [LocalDate]
     * rather than an instant because the time of day is a separate concern — the server pairs this date
     * with the definition's `deadline_time_of_day` through the same inclusive-end-of-day producer the
     * rule's `UNTIL` uses (`backend/src/time.rs`, `compute_occurrence_complete_by`).
     */
    data class RollingTo(val date: LocalDate) : NextDeadline

    /**
     * The rule decides. Either the mode is [CadenceMode.Fixed], or it is rolling but the cadence has no
     * stride expressible in days — a weekly rule's effective interval varies week to week (Mon→Wed is
     * two days, Wed→Mon is five) and monthly/yearly depend on calendar boundaries, so there is nothing
     * to add. Expand the grid and take the next firing after the one just resolved.
     */
    data object FromGrid : NextDeadline

    /**
     * The rolling re-anchor would land past the rule's inclusive `UNTIL`, so the series is finished —
     * the backend's `ScheduleAdvance::Ended`, the one outcome that **clears** `complete_by`.
     *
     * Without this guard a rolling chore walks straight past its own bound, and worse: the anchor it
     * would mint has `DTSTART > UNTIL`, which the `rrule` crate rejects outright and which once took a
     * user's entire calendar down (Deferno#428/#429).
     */
    data object Ended : NextDeadline
}

/**
 * Which rule governs this definition's next deadline after a firing completed at [doneAt].
 *
 * Four things about this are easy to get subtly wrong, and each is the server's behaviour rather than a
 * choice made here — parity is normative (ADR-0053 decision 5), so a "better" answer is a divergence:
 *
 * 1. **The `UNTIL` comparison is strict.** A candidate landing *exactly* on the bound day still
 *    schedules; only `candidate > until` reports [NextDeadline.Ended]. An off-by-one here turns the last
 *    legitimate firing into a cleared cursor.
 * 2. **There is no `COUNT` guard, deliberately.** The backend reads the bound through
 *    `RecurrenceEnd::until_local_date()`, which answers only for `OnDate` — so a rolling
 *    `daily; COUNT=3` chore re-anchors forever server-side and never reports Ended. Adding the
 *    sensible-looking count guard here would make this client disagree with the server about when a
 *    series ends, which is exactly the second specification this programme exists to remove. If that is
 *    wrong, it is wrong in Rust.
 * 3. **[zone] is the account's *current* zone, not the series' frozen `tzid`.** The backend resolves it
 *    with `resolve_user_tz_no_override`, so rolling advancement moves with the person while the grid
 *    stays where it was scheduled ([SeriesInputs.tzid]). Those are genuinely different zones for anyone
 *    who has moved country, and the asymmetry is the server's; pass the account zone here and the frozen
 *    zone to [expandOccurrenceGrid].
 * 4. **Only a *done* firing advances anything.** `Skipped` is terminal for the cascade but does **not**
 *    advance the cursor, and neither does `InProgress` — so the caller gates on
 *    [OccurrenceResolution.DoneOnTime] / [OccurrenceResolution.DoneLate] before calling. A client that
 *    advanced on any terminal resolution drifts one firing ahead of the server on every drop.
 */
fun nextDeadlineAfter(
    cadenceMode: CadenceMode,
    recurrence: Recurrence,
    doneAt: Instant,
    zone: TimeZone,
): NextDeadline {
    if (cadenceMode != CadenceMode.Rolling) return NextDeadline.FromGrid
    val stride = recurrence.cadence.intervalAsDays ?: return NextDeadline.FromGrid
    val candidate = doneAt.toLocalDateTime(zone).date.addDays(stride)
    val until = (recurrence.bound as? RecurrenceBound.OnDate)?.date
    // Strictly greater — see note 1. `Never` and `AfterCount` have no date bound at all — see note 2.
    if (until != null && candidate > until) return NextDeadline.Ended
    return NextDeadline.RollingTo(candidate)
}

/**
 * This cadence's stride as a whole number of days, or `null` when it has none — the port of the
 * backend's `Cadence::interval_as_days`, and the gate that decides whether a [CadenceMode.Rolling]
 * definition re-anchors off the completion or falls through to the grid.
 *
 * Only two cadences answer. [Cadence.Weekly] is excluded even though it looks periodic, because its
 * effective interval varies week to week; [Cadence.Monthly] and [Cadence.Yearly] because they depend on
 * calendar boundaries, which is the same reason [expandOccurrenceGrid] steps them by period rather than
 * by days.
 *
 * A stride of zero or less is `null`, not zero, mirroring the Rust's explicit `n > 0` guard: a chore
 * that re-anchored to `done_at + 0 days` would be due again the instant it was finished, forever.
 */
val Cadence.intervalAsDays: Int?
    get() = when (this) {
        is Cadence.Daily -> 1
        is Cadence.EveryNDays -> n.takeIf { it > 0 }
        is Cadence.Weekly, is Cadence.Monthly, is Cadence.Yearly,
        is Cadence.Custom, is Cadence.Unmodelled,
        -> null
    }
