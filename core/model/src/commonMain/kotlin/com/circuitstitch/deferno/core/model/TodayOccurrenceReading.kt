package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate

/**
 * What the [[Occurrence grid]] says about one day, for one definition — the *which days* half of the
 * reading, kept strictly apart from the *how did it go* half ([OccurrenceState]).
 *
 * Three values and not a `Boolean?`, because the two failure modes are unlike and a caller must not
 * collapse them: a grid this device **cannot reproduce** ([Unavailable]) is not a grid that says
 * **nothing fires** ([NotFiring]). That is the same distinction [Expansion] draws, carried one layer up
 * so the View cannot lose it — rendering "not scheduled today" for a rule we simply failed to expand
 * would state a fact we do not have.
 */
sealed interface DayFiring {

    /**
     * The grid cannot be reproduced here: a `Custom` rule, a cadence this build does not model, an
     * unknown zone, an unresolvable anchor — or the backend **elided** the series block, which means
     * "this device cannot reproduce that grid" and never "that grid has no exclusions" ([SeriesInputs]).
     */
    data object Unavailable : DayFiring

    /** The grid was reproduced and puts no firing on the day. */
    data object NotFiring : DayFiring

    /**
     * The grid puts [firing] on the day.
     *
     * A **cancelled** firing still arrives here rather than reading as [NotFiring]: the slot existed and
     * was called off, which is a different statement from the rule never having fired, and only the
     * caller can decide how to say it.
     */
    data class Fires(val firing: Firing) : DayFiring
}

/**
 * Today's complete reading for one recurring definition (ADR-0053 decision 4) — two independent
 * questions the detail must answer separately and must not conflate:
 *
 * - [firing] — *does anything fire today?* Answered by the offline expander, from the frozen anchor and
 *   the rule.
 * - [state] — *how did today go?* Answered by [resolveOccurrenceState] over the stored fact, coverage
 *   and the [[Definition state]].
 *
 * They are genuinely orthogonal, and the backend says so about its own field: `today_occurrence`
 * *"always describes today's date for this item; it does not mean the item is scheduled to fire
 * today."* A surface that renders one and calls it the other reports the series where the user asked
 * about the day.
 *
 * **Both halves are readings. Neither is ever persisted** — `occurrence_state` is never a column, and a
 * grid is computed for a window so caching one would cache an answer whose input moves.
 */
data class TodayOccurrence(
    val firing: DayFiring,
    val state: OccurrenceState,
) {
    /** Whether a firing lands on the day and was not called off — the plain "is something due" reading. */
    val isDue: Boolean get() = (firing as? DayFiring.Fires)?.firing?.isCancelled == false

    companion object {
        /** Nothing known: no grid, no fact, no coverage. What an unopened definition reads as. */
        val Unknown: TodayOccurrence = TodayOccurrence(DayFiring.Unavailable, OccurrenceState.Unknown)
    }
}

/**
 * Derive [TodayOccurrence] for [today]. Pure by construction — [today] is a parameter, never a clock
 * read, which is the entire point: a value computed against the clock and cached would still be
 * claiming "Scheduled" a week after the day passed.
 *
 * [fact] is the stored resolution for the day, and [covered] whether this device has actually synced
 * that range. **Look [fact] up by the firing's [Firing.slotDate], not by [today]**, whenever
 * [dayFiring] returns a rescheduled instance — a moved firing keeps its identity at the slot it moved
 * *from*, which is the date the occurrence routes and the fact table key on ([Firing.slotDate]).
 * [todayFactDate] does that lookup-date arithmetic for a caller.
 */
fun readTodayOccurrence(
    recurrence: Recurrence?,
    series: SeriesInputs?,
    definitionState: DefinitionState?,
    fact: OccurrenceFact?,
    covered: Boolean,
    today: LocalDate,
): TodayOccurrence = readTodayOccurrence(
    firing = dayFiring(recurrence, series, today),
    definitionState = definitionState,
    fact = fact,
    covered = covered,
    today = today,
)

/**
 * [readTodayOccurrence] over an **already-expanded** [firing].
 *
 * A caller that needs the firing itself — to correct the fact's lookup date through [factDateFor], as
 * the detail must — would otherwise expand the grid twice per emission. Expansion walks the rule slot
 * by slot, so this is the overload a render path should use; the convenience form above is for callers
 * that only want the answer.
 */
fun readTodayOccurrence(
    firing: DayFiring,
    definitionState: DefinitionState?,
    fact: OccurrenceFact?,
    covered: Boolean,
    today: LocalDate,
): TodayOccurrence = TodayOccurrence(
    firing = firing,
    state = resolveOccurrenceState(
        fact = fact,
        covered = covered,
        definitionState = definitionState,
        date = today,
        today = today,
    ),
)

/**
 * The date a caller must look the stored fact up under, to answer for [date] — the firing's
 * [Firing.slotDate] when the grid moved an instance onto [date], else [date] itself.
 *
 * Rescheduling is the only case where the two differ, and getting it wrong is a silent miss rather than
 * an error: the fact would be filed under the slot while the detail queried the render day, so a
 * completed-but-moved firing would read as unresolved.
 */
fun DayFiring.factDateFor(date: LocalDate): LocalDate =
    (this as? DayFiring.Fires)?.firing?.slotDate ?: date

/**
 * Whether the grid puts a firing on [date], and which.
 *
 * **The window is ±1 day, deliberately.** [expandOccurrenceGrid] reads its window in the series' frozen
 * zone — *"expand in the frozen zone, then project"* — which need not be the reader's zone, and an
 * override can move a firing's [Firing.date] off its own [Firing.slotDate]. A single-day window read in
 * the wrong zone would drop the very firing being asked about.
 *
 * The match is on [Firing.date] (where the firing *renders*), not [Firing.slotDate] (what it is
 * *identified* by), because the question this answers is "is something happening today".
 */
fun dayFiring(recurrence: Recurrence?, series: SeriesInputs?, date: LocalDate): DayFiring {
    val rule = recurrence ?: return DayFiring.Unavailable
    // The backend's ELISION, not an empty grid — see the class KDoc on SeriesInputs.
    val inputs = series ?: return DayFiring.Unavailable
    return when (val expansion = expandOccurrenceGrid(rule, inputs, date.addDays(-1), date.addDays(1))) {
        is Expansion.NotExpandable -> DayFiring.Unavailable
        is Expansion.Firings ->
            expansion.firings.firstOrNull { it.date == date }
                ?.let(DayFiring::Fires)
                ?: DayFiring.NotFiring
    }
}
