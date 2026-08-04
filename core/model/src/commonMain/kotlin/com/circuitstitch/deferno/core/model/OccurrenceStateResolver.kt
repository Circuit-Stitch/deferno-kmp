package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate

/**
 * Derive how one dated firing went — the render-time **reading** of CONTEXT.md → "Occurrence state"
 * ("a reading, never a stored value") and the contract ADR-0053 decision 4 turns on.
 *
 * Pure by construction: `today` is a parameter, never a clock read. That is the entire point. A value
 * computed against the clock and then cached would still be claiming "Scheduled" a week after the day
 * passed, which is the defect this whole programme exists to remove — so this function must stay
 * callable at render time, on any date, with the server gone forever.
 *
 * The arms, in order:
 * - **a stored fact** → its resolution, 1:1. What the server recorded is what happened; nothing is
 *   re-litigated on top of it.
 * - **no fact, outside coverage** → [OccurrenceState.Unknown]. This device has never looked at that
 *   date, and ignorance is not evidence.
 * - **no fact, inside coverage, `date >= today`** → [OccurrenceState.Scheduled]. Nothing has happened
 *   yet because nothing was due to happen yet.
 * - **no fact, inside coverage, `date < today`** → [OccurrenceState.Missed] on an Active definition,
 *   else [OccurrenceState.Skipped]. This mirrors the backend's own third arm: a past date with no
 *   record is Missed *only while the definition is live*. A shelved definition's past empty days are
 *   history, not a reproach — and per `archive_habit` the backend leaves `complete_by` untouched on
 *   archive, so a definition switched off in January would otherwise report every day since as
 *   overdue.
 * - **an unknown definition state** (`null` — the definition is not cached) → [OccurrenceState.Unknown],
 *   never Missed. Same rule as coverage: without the light switch we cannot tell history from
 *   neglect, so we say so.
 *
 * **`today` is the caller's local today, not UTC.** The backend cuts this split on
 * `Utc::now().date_naive()` while computing the same row's `complete_by` in the user's zone, so for
 * anyone west of UTC the server contradicts itself for the last hours of each day. Deriving in the
 * user's zone is right for the user; ADR-0053 decision 5 puts the fix in Rust, and no test here pins
 * the client to the server's UTC answer.
 */
fun resolveOccurrenceState(
    fact: OccurrenceFact?,
    covered: Boolean,
    definitionState: DefinitionState?,
    date: LocalDate,
    today: LocalDate,
): OccurrenceState {
    if (fact != null) return fact.resolution.toOccurrenceState()
    if (!covered || definitionState == null) return OccurrenceState.Unknown
    if (date >= today) return OccurrenceState.Scheduled
    return if (definitionState == DefinitionState.Active) OccurrenceState.Missed else OccurrenceState.Skipped
}

/**
 * Widen a stored [OccurrenceResolution] into the [OccurrenceState] reading. Total and lossless — every
 * stored variant has an identical-meaning reading; the reading's two extra members ([OccurrenceState.Missed],
 * [OccurrenceState.Unknown]) are exactly the ones that are *never* stored, which is why this direction
 * needs no fallback arm.
 */
fun OccurrenceResolution.toOccurrenceState(): OccurrenceState = when (this) {
    OccurrenceResolution.Scheduled -> OccurrenceState.Scheduled
    OccurrenceResolution.InProgress -> OccurrenceState.InProgress
    OccurrenceResolution.DoneOnTime -> OccurrenceState.DoneOnTime
    OccurrenceResolution.DoneLate -> OccurrenceState.DoneLate
    OccurrenceResolution.Skipped -> OccurrenceState.Skipped
}
