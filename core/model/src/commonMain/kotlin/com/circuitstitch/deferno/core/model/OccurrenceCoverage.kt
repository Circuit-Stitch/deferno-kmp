package com.circuitstitch.deferno.core.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Which dates this device has actually **synced** for one recurring definition (CONTEXT.md →
 * "Occurrence coverage"). It is the bookkeeping that makes "there is no row for 3 March" answerable:
 * inside coverage that absence is *evidence* (nothing was recorded, so the firing is unresolved);
 * outside it the absence is only *ignorance*, and the reading says **Unknown** rather than inventing
 * a Missed.
 *
 * Both bounds are **inclusive** — this records exactly the `?from=&to=` window the kind-scoped
 * occurrence endpoint was asked for, and those bounds are inclusive server-side.
 *
 * _Avoid_ conflating this with a cache (coverage records what was *fetched*, not what is retained)
 * or with the Activity ledger's sync cursor (a single delta axis — a different thing entirely).
 */
data class OccurrenceCoverage(
    val kind: ItemKind,
    val definitionId: String,
    val from: LocalDate,
    val to: LocalDate,
) {
    init {
        require(from <= to) { "coverage range is inverted: $from > $to" }
    }

    /** Whether [date] falls inside this range (both bounds inclusive). */
    fun covers(date: LocalDate): Boolean = date >= from && date <= to
}

/**
 * Fold [new] into this definition's synced ranges, coalescing **only** ranges that overlap or are
 * genuinely adjacent (`to + 1 day == from`).
 *
 * The restraint is the whole point. Merging two windows that have a gap between them would swallow
 * the gap into "synced", and every unsynced day inside it would then read as Missed instead of
 * Unknown — which is precisely the defect ADR-0053 was written to close, reintroduced one layer
 * down. Ranges for other definitions pass through untouched, and the result is kept sorted so the
 * merge is deterministic (and therefore testable).
 */
fun List<OccurrenceCoverage>.mergeCoverage(new: OccurrenceCoverage): List<OccurrenceCoverage> {
    val (mine, others) = partition { it.kind == new.kind && it.definitionId == new.definitionId }
    val merged = mutableListOf<OccurrenceCoverage>()
    var candidate = new
    for (range in mine.sortedBy { it.from }) {
        // Adjacent counts as touching: a range ending the day before the next begins leaves no
        // unsynced day between them, so joining them asserts nothing that was not actually fetched.
        val touches = range.from <= candidate.to.plusOneDay() && candidate.from <= range.to.plusOneDay()
        if (touches) {
            candidate = candidate.copy(
                from = minOf(candidate.from, range.from),
                to = maxOf(candidate.to, range.to),
            )
        } else {
            merged += range
        }
    }
    merged += candidate
    return others + merged.sortedBy { it.from }
}

/** Whether any synced range for that definition covers [date]. */
fun List<OccurrenceCoverage>.covers(kind: ItemKind, definitionId: String, date: LocalDate): Boolean =
    any { it.kind == kind && it.definitionId == definitionId && it.covers(date) }

/**
 * The next calendar day — the repo's existing `plus(1, DateTimeUnit.…)` idiom, so month and year
 * ends are handled by the calendar rather than by hand.
 */
private fun LocalDate.plusOneDay(): LocalDate = plus(1, DateTimeUnit.DAY)
