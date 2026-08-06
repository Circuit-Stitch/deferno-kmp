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
    /**
     * Whether [state] is a **stored** resolution rather than one derived from coverage and the date.
     *
     * A renderer needs this and cannot recover it from [state], which is the whole reason it is a field.
     * When the grid is [DayFiring.Unavailable] the honest line is "this device cannot say what fires
     * today" — *unless* a resolution was actually recorded, in which case that fact still stands and
     * should be shown. Every surface's TODAY cell documents exactly that rule.
     *
     * Inferring it from the value does not work: [OccurrenceState.Scheduled] is produced BOTH by
     * `resolveOccurrenceState`'s derived arm (covered, no fact, the day has not passed) AND by a genuine
     * stored [OccurrenceResolution.Scheduled] — a `?scope=this` reschedule writes exactly such a row. The
     * two are indistinguishable downstream, and guessing picks the confident reading, which is the wrong
     * direction: it renders "Scheduled" for a grid nobody could expand.
     */
    val isStoredResolution: Boolean = false,
) {
    /** Whether a firing lands on the day and was not called off — the plain "is something due" reading. */
    val isDue: Boolean get() = (firing as? DayFiring.Fires)?.firing?.isCancelled == false

    /**
     * Whether the day's *state* is something this device actually knows — a stored resolution, or a
     * derivation over a grid it could reproduce. False means the only honest line is "not available".
     *
     * The one predicate every TODAY cell needs, kept here so the three renderers cannot each write their
     * own slightly-different version of it (they did, and all three were wrong the same way).
     */
    val isStateKnown: Boolean get() = isStoredResolution || firing !is DayFiring.Unavailable

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
    // The provenance the resolver's return value cannot carry — see [TodayOccurrence.isStoredResolution].
    isStoredResolution = fact != null,
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
 * The match is on [Firing.date] (where the firing *renders*), not [Firing.slotDate] (what it is
 * *identified* by), because the question this answers is "is something happening today".
 *
 * **That mismatch is why the window cannot be a fixed ±1 day.** [expandOccurrenceGrid] filters its
 * window on the **slot**, deliberately — *"the window is filtered on the ORIGINAL slot, so a firing
 * moved outside it still comes back"*, matching the crate, which applies its range before it applies
 * overrides. So asking for slots `[date-1, date+1]` and then matching on the rendered date answers a
 * different question than the one posed: a firing rescheduled ONTO [date] from a slot further out is
 * never generated, and the day reads [DayFiring.NotFiring] — a confident "nothing is scheduled today"
 * on the exact day the user moved the occurrence to, and one that also strands [factDateFor], whose
 * entire job is to look that firing's fact up under the slot it moved from.
 *
 * A reschedule can be any distance, so no constant is large enough. The bound comes from the data
 * instead: [SeriesInputs.overrides] states every move the series has, so the window is widened to reach
 * the slot of any override that lands on [date]. Exact, and bounded by the overrides that exist.
 *
 * The residual ±1 is still needed and is a different concern — [expandOccurrenceGrid] reads its window
 * in the series' **frozen zone**, which need not be the reader's, so a slot can resolve a day either
 * side of its nominal date.
 */
fun dayFiring(recurrence: Recurrence?, series: SeriesInputs?, date: LocalDate): DayFiring {
    val rule = recurrence ?: return DayFiring.Unavailable
    // The backend's ELISION, not an empty grid — see the class KDoc on SeriesInputs.
    val inputs = series ?: return DayFiring.Unavailable

    // ±1 for frozen-zone skew, then widened to the slot of every override that MOVES onto `date`.
    var from = date.addDays(-1)
    var to = date.addDays(1)
    for (override in inputs.overrides) {
        if (override.movedToLocal?.date != date) continue
        val slot = override.recurrenceId.date
        // The slot gets its own ±1 for the same zone-skew reason the base window has one.
        if (slot.addDays(-1) < from) from = slot.addDays(-1)
        if (slot.addDays(1) > to) to = slot.addDays(1)
    }

    return when (val expansion = expandOccurrenceGrid(rule, inputs, from, to)) {
        is Expansion.NotExpandable -> DayFiring.Unavailable
        is Expansion.Firings ->
            expansion.firings.firstOrNull { it.date == date }
                ?.let(DayFiring::Fires)
                ?: DayFiring.NotFiring
    }
}
