package com.circuitstitch.deferno.core.model

/**
 * One agenda row paired with the render-time reading of how its firing went (ADR-0053 decisions 4
 * and 7).
 *
 * **The pairing is structural, and that is the design.** A [CalendarItem] alone cannot say how a
 * firing went — the feed stamps the *definition's* status onto every one of its firings, so an Active
 * Habit read `Open` on every chip forever however many check-ins it had, and archiving it flipped its
 * whole history to `Done`. Carrying [occurrence] alongside the row means the View is handed the
 * answer rather than deriving one, which is what stops the reading being re-derived — or, worse,
 * cached — at the edge.
 *
 * [occurrence] is `null` for a row that is not an actionable firing: a one-off dated Task, an
 * unresolved-kind row, or a synced external event ([CalendarItem.isActionableOccurrence] `false`).
 * Such a row renders from its own [CalendarItem.status], which stays genuinely meaningful for exactly
 * that case — a Task's working state *is* a fact about that item. Nullability is what keeps the two
 * apart: `working_state` is a non-null column with a defensive decode to `Open`, so "meaningless for a
 * firing" could never have been expressed by reinterpreting it.
 */
data class CalendarFiring(
    val item: CalendarItem,
    val occurrence: OccurrenceState?,
)
