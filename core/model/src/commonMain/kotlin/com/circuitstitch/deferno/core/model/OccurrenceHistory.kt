package com.circuitstitch.deferno.core.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.math.roundToInt

/**
 * The Occurrence **history strip** and the statistics read off it — a linear tile per day, an
 * on-time / late / dropped tally over that strip, and a Habit streak.
 *
 * A port of webui's `webui/src/utils/occurrenceHistory.ts` (236 lines at the reference checkout's
 * HEAD), which is normative: ADR-0053 decision 5 says parity is with the Rust and the web client
 * that already ships against it, and that the client follows rather than forking a second
 * specification. Every function below is the same algorithm, expressed over this codebase's domain
 * types instead of the web client's wire strings — [OccurrenceState] for the render-time reading,
 * [OccurrenceResolution] for the stored fact, [LocalDate] instead of a `YYYY-MM-DD` string.
 *
 * **Pure by construction.** `today` is a parameter to [computeTileStrip], never a clock read, for
 * exactly the reason [resolveOccurrenceState] is: the strip is a function of today, so a strip built
 * against a captured clock and then held would keep claiming the same right edge a week later.
 * Callers on all four hosts pass a snapshot today at construction; when that becomes live (#392, the
 * timezone restructure) nothing here changes, because nothing here remembers it.
 *
 * The whole file is arithmetic over the local calendar — no [kotlin.time.Instant], no zone, no UTC.
 * A history strip answers "which days", and a day is a day wherever the person is standing.
 */

/**
 * The four buckets the strip and the stats row understand — deliberately fewer than
 * [OccurrenceState] has members, because a *visualisation* draws four colours and several distinct
 * readings collapse onto one of them (`Missed` and `Skipped` are both "did not happen"; `Scheduled`
 * and `InProgress` are both "not resolved yet").
 *
 * "No bucket at all" is expressed as `null` rather than a fifth member — see [StripCell.status].
 * That keeps the absence of a firing distinguishable from an unresolved one, which is the same
 * distinction [OccurrenceState.Unknown] exists to preserve one layer up.
 */
enum class StripCellStatus {
    Done,
    Late,
    Dropped,
    Scheduled,
}

/**
 * One tile in the linear strip — exactly one per day in the selected window.
 *
 * [status] is `null` when the day carries no firing at all (or one whose reading is
 * [OccurrenceState.Unknown]); [computeHeatmapStats] and [computeHabitStreak] both treat that as "not
 * done", never as "done nothing wrong".
 *
 * [isFuture] is *structurally* false for every cell [computeTileStrip] builds, because the strip's
 * right edge is always anchored at today. It is carried anyway: it is the field
 * [computeHabitStreak] skips on, so a caller that hands in a hand-built strip extending past today
 * gets the web client's behaviour rather than a broken streak.
 */
data class StripCell(
    val date: LocalDate,
    val status: StripCellStatus?,
    val isToday: Boolean,
    val isFuture: Boolean,
)

/**
 * The tally over a strip. [total] is **derived**, not stored: it is `onTime + late + dropped` by
 * definition, and a fourth constructor parameter could be handed a number that disagrees with the
 * three it sums. The web client stores it (`occurrenceHistory.ts` `HeatmapStats.total`) because a
 * TypeScript object literal has no other option; the same value here is a computed property, in the
 * house idiom of [OccurrenceState.isResolved] and [OccurrenceResolution.isTerminal].
 *
 * Note what is *not* in the sum: [StripCellStatus.Scheduled] cells and empty ones. An unresolved day
 * is not evidence of anything, so it moves neither numerator nor denominator.
 */
data class HeatmapStats(
    val onTime: Int,
    val late: Int,
    val dropped: Int,
) {
    /** `onTime + late + dropped` — the resolved days, and only those. */
    val total: Int get() = onTime + late + dropped
}

/**
 * A Habit's streak over a strip: [current] counts back from today, [best] is the longest run
 * anywhere in the window.
 *
 * [delta] — the mockup's "+N since last break" — is [current], and is a computed property saying so
 * rather than a field that could drift from it. The two are the same number by construction: a
 * current streak *is* the increase since the last non-done day.
 */
data class HabitStreak(
    val current: Int,
    val best: Int,
) {
    /** The increase since the last non-done day — identical to [current], by construction. */
    val delta: Int get() = current
}

// ── Calendar arithmetic ───────────────────────────────────────────────────────────────────────

/**
 * [days] later on the local calendar (negative walks backwards) — the strip's only date arithmetic.
 *
 * Delegated to kotlinx-datetime's calendar `plus` rather than epoch-day maths so month ends and year
 * ends are handled by the calendar: 31 May + 1 is 1 June and 31 December + 1 is 1 January, with no
 * table of month lengths anywhere in this file. The web client's `addDaysIso` gets the same property
 * by round-tripping through a local `Date`; neither goes near UTC, and neither may, because a strip
 * built through an instant would lose or gain a day for anyone not on Greenwich.
 */
fun LocalDate.addDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

// ── Bucketing ─────────────────────────────────────────────────────────────────────────────────

/**
 * Bucket a render-time **reading** — the port of the web client's `bucketChoreStatus`, whose input is
 * that codebase's *derived* chore union (`scheduled`/`missed`/`in_progress`/`done_on_time`/
 * `done_late`/`skipped`, `webui/src/types/index.ts:847-853`). [OccurrenceState] is the same union
 * here, plus [OccurrenceState.Unknown] which TypeScript has no member for.
 *
 * **Ported from the body, not from the doc comment.** `bucketChoreStatus`'s own KDoc
 * (`occurrenceHistory.ts:78-82`) claims it returns null for `scheduled` and `in_progress`; its switch
 * (`:94-99`) returns `"scheduled"` for both. The code is what ships and what its test suite pins
 * (`occurrenceHistory.test.ts` — "maps scheduled and in_progress → scheduled"), so the code is what
 * was ported. The comment is stale, and repeating a stale comment into a second language is how a
 * mistake becomes a specification.
 *
 * The one member with no counterpart to port is [OccurrenceState.Unknown], which maps to `null` — an
 * empty tile. That is the only honest cell for it: the reading means this device never synced the
 * day, so drawing it as [StripCellStatus.Scheduled] would assert a firing was pending, and drawing it
 * as [StripCellStatus.Dropped] would assert one was abandoned. Neither is known. `null` is also
 * already the strip's word for "no firing here", and the two really are the same picture.
 */
fun bucketOccurrenceState(state: OccurrenceState): StripCellStatus? = when (state) {
    OccurrenceState.DoneOnTime -> StripCellStatus.Done
    OccurrenceState.DoneLate -> StripCellStatus.Late
    // Skipped and Missed are one colour: the strip draws what did not happen, not why.
    OccurrenceState.Skipped, OccurrenceState.Missed -> StripCellStatus.Dropped
    OccurrenceState.Scheduled, OccurrenceState.InProgress -> StripCellStatus.Scheduled
    OccurrenceState.Unknown -> null
}

/**
 * Bucket a stored **fact** — the port of the web client's `bucketOccurrenceStatus`
 * (`occurrenceHistory.ts:113-131`), over the unified five-variant occurrence status
 * (`webui/src/types/index.ts:867-872`). [OccurrenceResolution] is that enum exactly, including the
 * genuinely stored `Scheduled` an event row can hold.
 *
 * Unlike [bucketOccurrenceState] this never returns null: every stored resolution is a fact about the
 * day, and a fact always has a colour.
 *
 * The web client needs a defensive `default` arm here (mapping the legacy `"skipped"` spelling of
 * Dropped, and anything unrecognised to `scheduled` — "never claim done for something we can't
 * read"). This port has no such arm and needs none, in both directions:
 * - the legacy `skipped` / v0.2 `dropped` spellings are the *same* [OccurrenceResolution.Skipped]
 *   here, collapsed at the wire boundary rather than in a visualisation helper;
 * - an unreadable token can never reach this function, because the DTO enums coerce one to their own
 *   `Unknown` member long before a fact is built.
 * What remains of the defence is stronger than the original: the `when` is exhaustive with no `else`,
 * so a sixth resolution would fail this file to compile rather than silently bucket as scheduled.
 */
fun bucketResolution(resolution: OccurrenceResolution): StripCellStatus = when (resolution) {
    OccurrenceResolution.DoneOnTime -> StripCellStatus.Done
    OccurrenceResolution.DoneLate -> StripCellStatus.Late
    OccurrenceResolution.Skipped -> StripCellStatus.Dropped
    OccurrenceResolution.Scheduled, OccurrenceResolution.InProgress -> StripCellStatus.Scheduled
}

/**
 * Bucket a **Habit** fact — the port of `bucketHabitOccurrence` (`occurrenceHistory.ts:140-144`).
 *
 * A Habit has only done and not-done: the wire row is `{ habit_id, date, done_at }`
 * (`backend/src/models/occurrence.rs:31-36`) with no status column and no deadline, so there is
 * nothing to be late against and nothing that records a decision not to do it. This reads
 * [OccurrenceFact.doneAt] and nothing else, which is what makes "a Habit strip never shows late or
 * dropped" a structural property rather than a convention some later mapper could break.
 *
 * A not-done past day therefore buckets as [StripCellStatus.Scheduled], not Dropped — the same
 * restraint the web client records at `occurrenceHistory.ts:133-138` ("no taxonomy to call a missed
 * habit 'dropped' without a server signal"). The Scheduled-vs-Missed reading is
 * [resolveOccurrenceState]'s job and needs `today`, coverage and the parent's [DefinitionState];
 * a bucketer has none of those and must not guess.
 */
fun bucketHabitFact(fact: OccurrenceFact): StripCellStatus =
    if (fact.doneAt != null) StripCellStatus.Done else StripCellStatus.Scheduled

/**
 * Bucket any stored fact, routing by [OccurrenceFact.kind] — the dispatch the web client performs at
 * its call sites (`HeatmapForKind.tsx` picks `bucketHabitOccurrence` for habits and
 * `bucketOccurrenceStatus` for events) rather than in `occurrenceHistory.ts`. It is one function here
 * so a caller holding a mixed `List<OccurrenceFact>` never fans a `when (kind)` of its own — the
 * dispatch that #385 spent a PR removing from the Plan.
 *
 * Habits go through [bucketHabitFact] and are read from `done_at` alone; everything else goes through
 * [bucketResolution]. That is not redundancy with whatever resolution a habit fact happens to carry:
 * it is the guarantee that a habit can never be drawn late or dropped even if some future mapper
 * writes a punctuality the wire cannot express.
 *
 * A [ItemKind.Task] never produces a fact — a Task is not a recurring definition and has no firings —
 * but it is listed rather than swept into an `else` so that a fifth kind fails to compile here.
 */
fun bucketFact(fact: OccurrenceFact): StripCellStatus = when (fact.kind) {
    ItemKind.Habit -> bucketHabitFact(fact)
    ItemKind.Task, ItemKind.Chore, ItemKind.Event -> bucketResolution(fact.resolution)
}

// ── Strip + stats ─────────────────────────────────────────────────────────────────────────────

/**
 * Build the linear tile strip: [rangeDays] cells, **oldest on the left**, today on the right.
 *
 * The right edge is always anchored at [today] — a wider range extends further into the *past*, never
 * into lookahead, which is the web client's convention (`occurrenceHistory.ts:155-162`) and the range
 * picker's whole meaning: 30 / 60 / 90 days of history. One consequence worth stating because a
 * reader will otherwise wonder why [StripCell.isFuture] exists at all: it is false on every cell this
 * function builds, by construction.
 *
 * Days absent from [statusByDate] get a `null` status. That is deliberately *not* a lookup failure to
 * paper over — an empty tile is a real reading, and it is the caller's job to have already turned
 * facts + coverage + today into buckets (via [resolveOccurrenceState] then [bucketOccurrenceState], or
 * [bucketFact] where only stored facts are being drawn).
 *
 * A [rangeDays] of zero or less yields an empty strip, matching the source's `for (offset =
 * rangeDays - 1; offset >= 0; offset--)` — no exception, because an empty window is a coherent thing
 * to ask for and the stats below all read zero over it.
 */
fun computeTileStrip(
    rangeDays: Int,
    today: LocalDate,
    statusByDate: Map<LocalDate, StripCellStatus>,
): List<StripCell> {
    val cells = ArrayList<StripCell>(rangeDays.coerceAtLeast(0))
    for (offset in rangeDays - 1 downTo 0) {
        val date = today.addDays(-offset)
        cells += StripCell(
            date = date,
            status = statusByDate[date],
            isToday = date == today,
            isFuture = date > today,
        )
    }
    return cells
}

/**
 * Tally a strip into [HeatmapStats].
 *
 * [StripCellStatus.Scheduled] and empty cells contribute to **nothing** — not to their own count,
 * and not to [HeatmapStats.total]. This is the load-bearing line of the whole tally: an unresolved
 * day is not a failure, so counting it into the total would silently deflate every rate computed from
 * it. The `when` names both non-contributing cases as an explicit arm rather than letting them fall
 * off the end, so the omission reads as a decision.
 */
fun computeHeatmapStats(cells: List<StripCell>): HeatmapStats {
    var onTime = 0
    var late = 0
    var dropped = 0
    for (cell in cells) {
        when (cell.status) {
            StripCellStatus.Done -> onTime++
            StripCellStatus.Late -> late++
            StripCellStatus.Dropped -> dropped++
            // Unresolved and empty days are evidence of nothing and are counted nowhere.
            StripCellStatus.Scheduled, null -> Unit
        }
    }
    return HeatmapStats(onTime = onTime, late = late, dropped = dropped)
}

/**
 * The on-time rate as a whole percentage: `onTime / (onTime + late)`, rounded half-up.
 *
 * Two things about the shape of this answer, both deliberate:
 * - **Dropped is not in the denominator.** The rate answers "when this got done, was it on time" —
 *   a question about punctuality, not about adherence. Folding drops in would make it a different
 *   statistic wearing this one's name.
 * - **It returns `null`, never `0`, when the denominator is zero.** Nothing done and nothing late is
 *   *no data*, and rendering that as "0%" would read as a total failure to a person who has simply
 *   not started. The caller renders an em-dash.
 */
fun computeOnTimeRate(stats: HeatmapStats): Int? {
    val denominator = stats.onTime + stats.late
    if (denominator == 0) return null
    return (stats.onTime * 100.0 / denominator).roundToInt()
}

/**
 * The Habit streak over a strip: [HabitStreak.current] walks right-to-left from today counting
 * consecutive [StripCellStatus.Done] cells and stops at the first cell that is anything else —
 * including an empty one. [HabitStreak.best] is a separate left-to-right scan for the longest done
 * run anywhere in the window, so a broken streak still remembers its record.
 *
 * The two scans are genuinely independent and must stay so: a run that ended yesterday leaves
 * `current = 0` with `best` intact, which is the case that matters and the one a single fused scan
 * gets wrong. An empty or [StripCellStatus.Scheduled] cell in the past breaks the current streak
 * — a day with no check-in is not a check-in.
 *
 * Future cells are skipped rather than breaking, so a strip that a caller built past today (which
 * [computeTileStrip] never does) reads its streak from today backwards instead of reporting zero.
 */
fun computeHabitStreak(cells: List<StripCell>): HabitStreak {
    var current = 0
    for (index in cells.indices.reversed()) {
        val cell = cells[index]
        if (cell.isFuture) continue
        if (cell.status == StripCellStatus.Done) current++ else break
    }

    var best = 0
    var run = 0
    for (cell in cells) {
        if (cell.status == StripCellStatus.Done) {
            run++
            if (run > best) best = run
        } else {
            run = 0
        }
    }

    return HabitStreak(current = current, best = best)
}
