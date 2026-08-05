package com.circuitstitch.deferno.core.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Reproduce a recurring definition's [Occurrence grid] offline — the one place date arithmetic lives,
 * for all four platforms (ADR-0053 decision 1).
 *
 * Pure by construction: no clock read, no device zone, no I/O. Past and future windows alike. That is
 * the entire point — a client whose premise is that the server may never return cannot cache an answer
 * the server computed against *its* clock on the day of the fetch.
 *
 * **Parity is with the Rust, and the Rust is normative** (ADR-0053 decision 5). Expansion server-side is
 * `Recurrence::to_rrule(tz)` → `defernodate 0.2.0::expand_series` → `rrule 0.14.0` (a python-dateutil
 * port). Every rule below was read out of those three and then *measured* against them; the golden
 * corpus in `commonTest` regenerates from the same code, so a Rust change that moves the grid breaks
 * this rather than silently drifting it. Where the corpus and this file disagree, this file is wrong.
 * Where the corpus itself is wrong, the fix lands in Rust and the corpus regenerates.
 *
 * **No arithmetic downstream.** `RecurrenceReading`'s KDoc already fixes that norm for the display
 * layer — every rule the platforms must agree on is applied once, here, and cannot be re-litigated by a
 * renderer. This extends it from *readings* to *dates*.
 *
 * ## The semantics, and where each came from
 *
 * - **Skip, never clamp.** `Monthly(DayOfMonth(31))` emits `BYMONTHDAY=31`
 *   (`backend/src/models/recurrence.rs:266-280`); February produces nothing at all, and there is no
 *   "last day of the month" fallback. `Yearly(month = 2, day = 29)` likewise fires three years in four.
 *   This is why nothing here goes near `LocalDate.plus(1, MONTH)` — that **clamps** (31 Jan + 1 month is
 *   28 Feb), which is precisely the answer the server does not give. Periods are stepped from the
 *   *first* of the month and the day is re-attached, so an impossible date simply has no firing.
 * - **`DTSTART` is emitted only if it satisfies the `BY…` parts.** A weekly rule anchored on a Wednesday
 *   but firing on Mondays starts on the *following Monday*, not on the anchor. A slot before the anchor
 *   is skipped and — measured — does **not** consume a `COUNT`.
 * - **A stride's phase comes entirely from the anchor.** `EveryNDays` emits `FREQ=DAILY;INTERVAL=n` with
 *   no `BY…` part (`recurrence.rs:261`), so a client striding from *today*, or from the live cursor, is
 *   off by however far that cursor has walked. Monthly/yearly intervals count from the anchor's period
 *   the same way, and a period that yields no valid date still consumes its interval slot.
 * - **Weekly emits no `INTERVAL` and no `WKST`** (`recurrence.rs:262-265`), and with the interval always
 *   1 every week is in the set — so a weekly rule is exactly "every date from the anchor onward whose
 *   weekday is listed", and `WKST` cannot matter. It is pinned by the corpus rather than left to a
 *   client to default to Sunday.
 * - **Two bounds, opposite inclusivity, both live at once.** The rule's `UNTIL` is **inclusive** and is
 *   the canonical end-of-day sentinel instant in the frozen zone; [SeriesInputs.untilUtc] is
 *   **exclusive**. See [inclusiveEndOfDayInstant] and [SeriesInputs.untilUtc].
 * - **An `EXDATE`'d firing still consumes a `COUNT`** — RFC 5545 excludes after generation.
 * - **Expand in the frozen zone, then project.** Firing dates are the frozen zone's calendar dates, so
 *   [from]/[to] are read in that zone too. Someone who moves country keeps the grid they scheduled.
 * - **Overrides key on the original local wall time**, carried internally and projected to a date only
 *   at the edge — otherwise a rescheduled instance loses the rule slot it came from.
 *
 * ## Daylight saving is measured, not ported
 *
 * The instants themselves come from the `rrule` crate, and nothing in either repository documents,
 * tests or constrains its ambiguous/gap behaviour — so it was captured empirically and is pinned by the
 * corpus. What it actually does is `add_time_to_date` (`rrule-0.14.0/src/iter/utils.rs:76-87`):
 *
 * 1. if the wall time resolves to exactly **one** instant, use it;
 * 2. otherwise take **local midnight of that date** — again only if *it* resolves to exactly one
 *    instant — and add the time as an **elapsed duration** from midnight;
 * 3. otherwise the firing is **dropped entirely**, and a dropped firing does not consume a `COUNT`.
 *
 * Step 3 is not a corner case anybody would have guessed: in a zone whose transition is *at midnight*
 * (`America/Santiago`, `Asia/Beirut`) the whole day vanishes from the grid. `kotlinx.datetime`'s own
 * `toInstant` would happily shift it forward instead, which is why [resolveSlot] reimplements chrono's
 * `LocalResult` through [candidatesIn] rather than leaning on the library's resolution.
 *
 * Steps 1–2 do agree with `kotlinx.datetime` on the instant for every ordinary transition (a gap shifts
 * forward by the gap's size; a fold takes the earlier offset) — verified for one-hour and thirty-minute
 * gaps — but they are reimplemented anyway, because agreeing by coincidence is not a specification.
 *
 * A separate, stricter rule governs the anchor and the excluded dates: those are `DTSTART` and `EXDATE`
 * *lines*, and they go through the crate's **parser**, which hard-errors on a gap **and** on a fold
 * (`rrule-0.14.0/src/parser/datetime.rs:68-79`). An unresolvable anchor therefore refuses the whole
 * grid rather than nudging it — see [ExpansionRefusal.AnchorNotResolvable].
 *
 * @param from first day of the window, **inclusive**, read in the frozen zone.
 * @param to last day of the window, **inclusive**, read in the frozen zone.
 */
fun expandOccurrenceGrid(
    recurrence: Recurrence,
    series: SeriesInputs,
    from: LocalDate,
    to: LocalDate,
): Expansion {
    require(from <= to) { "expansion window is inverted: $from > $to" }

    val zone = runCatching { TimeZone.of(series.tzid) }
        .getOrElse { if (it is IllegalTimeZoneException) null else throw it }
        ?: return Expansion.NotExpandable(ExpansionRefusal.UnknownTimeZone(series.tzid))

    val rule = recurrence.cadence.toGridRule()
        ?: return Expansion.NotExpandable(recurrence.cadence.refusal())

    // DTSTART goes through the crate's parser, which demands exactly one instant.
    val anchorInstant = series.anchorLocal.candidatesIn(zone).singleOrNull()
        ?: return Expansion.NotExpandable(ExpansionRefusal.AnchorNotResolvable(series.anchorLocal))

    val excluded = HashSet<Instant>(series.exdates.size)
    for (exdate in series.exdates) {
        // EXDATE lines go through the same parser, and an unresolvable one fails the whole expansion
        // rather than being quietly ignored — matching `rrule`, which returns a parse error.
        excluded += exdate.candidatesIn(zone).singleOrNull()
            ?: return Expansion.NotExpandable(ExpansionRefusal.ExcludedDateNotResolvable(exdate))
    }

    val ruleUntil = (recurrence.bound as? RecurrenceBound.OnDate)?.let { inclusiveEndOfDayInstant(it.date, zone) }
    if (ruleUntil != null && anchorInstant > ruleUntil) {
        // `rrule` rejects a DTSTART > UNTIL series outright, which once bricked a whole calendar
        // (Deferno#428/#429). The backend clamps the anchor when it repairs a series; a row that
        // predates that guard still reaches us, and refusing is the honest reading of it.
        return Expansion.NotExpandable(ExpansionRefusal.AnchorAfterBound)
    }
    val countLimit = (recurrence.bound as? RecurrenceBound.AfterCount)?.n

    val overridesBySlot = series.overrides.associateBy { it.recurrenceId }
    val time = series.anchorLocal.time
    val firings = ArrayList<Firing>()
    var generated = 0

    // One extra day of slack past `to`: a midnight-crossing transition can move a slot's *resolved*
    // date forward, and the window is filtered on the resolved date, not the slot's nominal one.
    for (slotDate in rule.slotDates(series.anchorLocal.date, to.addDays(1))) {
        if (countLimit != null && generated >= countLimit) break

        // A slot the zone cannot place is never generated — so it is never counted either.
        val instant = resolveSlot(slotDate, time, zone) ?: continue
        if (instant < anchorInstant) continue
        if (ruleUntil != null && instant > ruleUntil) break

        generated++
        if (instant in excluded) continue
        if (series.untilUtc != null && instant >= series.untilUtc) continue

        // `naive_local()` — the resolved wall time, which is what an override keys on and what the
        // firing's date is read from. For a shifted slot this is NOT the wall time we asked for.
        val recurrenceId = instant.toLocalDateTime(zone)
        // The window is filtered on the ORIGINAL slot, so a firing moved outside it still comes back —
        // matching `expand_series`, which applies its range before it applies overrides.
        if (recurrenceId.date < from || recurrenceId.date > to) continue

        val override = overridesBySlot[recurrenceId]
        firings += Firing(
            recurrenceId = recurrenceId,
            startLocal = override?.movedToLocal ?: recurrenceId,
            isCancelled = override?.isCancelled == true,
            isOverride = override != null,
        )
        if (firings.size >= MAX_FIRINGS) break
    }
    return Expansion.Firings(firings)
}

/**
 * The result of an expansion — **two distinct values, never one**. "We cannot show this schedule
 * offline" and "nothing is scheduled" must not render the same, which is the same distinction ADR-0053
 * decision 4 already draws between a day outside [OccurrenceCoverage] and a day inside it with no fact.
 * A caller cannot reach an empty list without having matched [Firings] first.
 */
sealed interface Expansion {

    /** The window's firings in slot order, ascending. **May be empty** — that means nothing fires. */
    data class Firings(val firings: List<Firing>) : Expansion

    /** No grid can be produced. The rule itself still renders; see [ExpansionRefusal]. */
    data class NotExpandable(val reason: ExpansionRefusal) : Expansion
}

/**
 * One firing of the grid. Two wall times, deliberately: [recurrenceId] is the slot the rule produced
 * and [startLocal] is where it actually lands, and a rescheduled instance is the only case where they
 * differ.
 *
 * A **cancelled** firing is present, flagged — not absent. `expand_series` returns it that way
 * (`defernodate/src/expand.rs:83-88`), and a caller rendering a grid needs to know the slot existed.
 */
data class Firing(
    /**
     * The rule slot (RFC 5545 `RECURRENCE-ID`) — the **resolved** wall time, so for a firing the zone
     * shifted this is the shifted value, matching what an override must key on.
     */
    val recurrenceId: LocalDateTime,
    /** Where the firing actually lands: [SeriesOverride.movedToLocal] when moved, else [recurrenceId]. */
    val startLocal: LocalDateTime,
    val isCancelled: Boolean = false,
    val isOverride: Boolean = false,
) {
    /** The day this firing **renders** on. */
    val date: LocalDate get() = startLocal.date

    /**
     * The day this firing is **identified** by — `OccurrenceTargets.of`'s date segment, and the day the
     * occurrence endpoints key on. Equal to [date] for everything except a rescheduled instance, whose
     * identity stays with the slot it was moved *from*.
     */
    val slotDate: LocalDate get() = recurrenceId.date
}

/**
 * Why a grid cannot be produced. Each arm is a fact about the *inputs*, never about the window — an
 * empty window is [Expansion.Firings] with no entries.
 */
sealed interface ExpansionRefusal {

    /**
     * A raw RFC 5545 rule. The backend *declares* the same posture — "the recurrence expander treats
     * this as 'unknown cadence' (no occurrence expansion until a follow-up adds support)"
     * (`recurrence.rs:34-37`) — but be careful with that quote: it is a **doc comment, not code**.
     * `to_rrule` hands the raw rule straight to the crate (`recurrence.rs:294`) and nothing on the
     * expansion path guards it, so the server does in fact expand a `Custom` rule today.
     *
     * Refusing is still the right answer, and for a sharper reason than the comment gives. `to_rrule`
     * short-circuits the **bound** for `Custom` and appends nothing (`recurrence.rs:314-316`, pinned
     * by its own test at `:780-784`), so a `Custom` rule carrying a structured `end` is expanded
     * server-side *without* it. Expanding `Custom` here would mean either reproducing that data loss
     * or contradicting the server — and it would mean writing a general RFC 5545 parser, which is
     * exactly the second specification this file exists to avoid. `CadenceReading.Custom` still
     * renders "Custom schedule" throughout.
     */
    data object CustomCadence : ExpansionRefusal

    /** A cadence token this build cannot place (#382). There is nothing to expand from. */
    data class UnmodelledCadence(val rawType: String) : ExpansionRefusal

    /**
     * A [Cadence.Monthly] whose anchor did not survive the wire. The Kotlin models `on` as nullable so
     * an unreadable anchor still renders "Monthly"; the Rust makes it mandatory. Guessing the day of
     * month from the anchor would invent a grid the server does not have.
     */
    data object MonthlyWithoutAnchor : ExpansionRefusal

    /** A `nth` outside `-4..5`, which `rrule` rejects as a validation error rather than expanding. */
    data class UnplaceableMonthlyAnchor(val anchor: MonthlyAnchor) : ExpansionRefusal

    /** A weekday token this build's wire vocabulary cannot place, or a weekly rule with no days at all. */
    data class UnplaceableWeekday(val days: List<String>) : ExpansionRefusal

    /** An IANA zone this build's tzdb does not know. */
    data class UnknownTimeZone(val tzid: String) : ExpansionRefusal

    /** [SeriesInputs.anchorLocal] falls in a DST gap or a fall-back fold; `DTSTART` will not parse. */
    data class AnchorNotResolvable(val anchorLocal: LocalDateTime) : ExpansionRefusal

    /** One of [SeriesInputs.exdates] falls in a DST gap or fold; the `EXDATE` line will not parse. */
    data class ExcludedDateNotResolvable(val exdate: LocalDateTime) : ExpansionRefusal

    /** The anchor sits after the rule's inclusive `UNTIL`; `rrule` rejects such a series outright. */
    data object AnchorAfterBound : ExpansionRefusal
}

// ── Cadence → generation strategy ─────────────────────────────────────────────────────────────────

/**
 * The subset of RFC 5545 this expander has to implement — exactly what `Cadence::to_rrule` can emit
 * (`recurrence.rs:258-297`) and not one clause more.
 */
private sealed interface GridRule {

    /** Slot dates in ascending order, from [anchorDate]'s period up to and including [horizon]. */
    fun slotDates(anchorDate: LocalDate, horizon: LocalDate): Sequence<LocalDate>

    /** `FREQ=DAILY` / `FREQ=DAILY;INTERVAL=n`. */
    data class Daily(val stride: Int) : GridRule {
        override fun slotDates(anchorDate: LocalDate, horizon: LocalDate): Sequence<LocalDate> {
            // `INTERVAL=0` is not rejected by `rrule`; it simply yields nothing at all.
            if (stride < 1) return emptySequence()
            return generateSequence(anchorDate) { it.addDays(stride) }.takeWhile { it <= horizon }
        }
    }

    /** `FREQ=WEEKLY;BYDAY=…` — no `INTERVAL`, so every week is in the set and `WKST` is inert. */
    data class Weekly(val days: Set<DayOfWeek>) : GridRule {
        override fun slotDates(anchorDate: LocalDate, horizon: LocalDate): Sequence<LocalDate> =
            generateSequence(anchorDate) { it.addDays(1) }
                .takeWhile { it <= horizon }
                .filter { it.dayOfWeek in days }
    }

    /** `FREQ=MONTHLY[;INTERVAL=n];BYMONTHDAY=d` — a month without that day fires nothing. */
    data class MonthlyDay(val interval: Int, val day: Int) : GridRule {
        override fun slotDates(anchorDate: LocalDate, horizon: LocalDate): Sequence<LocalDate> =
            monthStarts(anchorDate, horizon, interval).mapNotNull { it.withDayOfMonth(day) }
    }

    /** `FREQ=MONTHLY[;INTERVAL=n];BYDAY={nth}{code}`, `nth` negative counting from the month's end. */
    data class MonthlyWeekday(val interval: Int, val nth: Int, val weekday: DayOfWeek) : GridRule {
        override fun slotDates(anchorDate: LocalDate, horizon: LocalDate): Sequence<LocalDate> =
            monthStarts(anchorDate, horizon, interval).mapNotNull { it.nthWeekdayOfMonth(nth, weekday) }
    }

    /** `FREQ=YEARLY[;INTERVAL=n];BYMONTH=m;BYMONTHDAY=d` — 29 February fires in leap years only. */
    data class Yearly(val interval: Int, val month: Int, val day: Int) : GridRule {
        override fun slotDates(anchorDate: LocalDate, horizon: LocalDate): Sequence<LocalDate> {
            val step = interval.coerceAtLeast(1)
            if (month !in 1..12) return emptySequence()
            return generateSequence(LocalDate(anchorDate.year, 1, 1)) { it.plus(step, DateTimeUnit.YEAR) }
                .takeWhile { it.year <= horizon.year }
                .mapNotNull { localDateOrNull(it.year, month, day) }
                .takeWhile { it <= horizon }
        }
    }
}

/** `null` when the cadence has no grid at all; see [refusal] for which refusal that is. */
private fun Cadence.toGridRule(): GridRule? = when (this) {
    is Cadence.Daily -> GridRule.Daily(stride = 1)
    is Cadence.EveryNDays -> GridRule.Daily(stride = n)
    // EVERY token must place — a partial grid is worse than none (ADR-0053: absent, not empty). The
    // check is on the parsed list, not on the deduplicated set, because a repeated day is legal and
    // the crate simply collapses it; only an *unreadable* token is a refusal.
    is Cadence.Weekly -> days.map { wireWeekday(it) }
        .takeIf { parsed -> parsed.isNotEmpty() && parsed.all { it != null } }
        ?.let { parsed -> GridRule.Weekly(parsed.filterNotNull().toSet()) }
    is Cadence.Monthly -> when (val anchor = on) {
        null -> null
        is MonthlyAnchor.DayOfMonth -> anchor.day.takeIf { it in 1..31 }
            ?.let { GridRule.MonthlyDay(interval, it) }
        is MonthlyAnchor.NthWeekday -> wireWeekday(anchor.weekday)
            ?.takeIf { anchor.nth in -4..5 }
            ?.let { GridRule.MonthlyWeekday(interval, anchor.nth, it) }
    }
    is Cadence.Yearly -> GridRule.Yearly(interval, month, day)
    is Cadence.Custom, is Cadence.Unmodelled -> null
}

private fun Cadence.refusal(): ExpansionRefusal = when (this) {
    is Cadence.Custom -> ExpansionRefusal.CustomCadence
    is Cadence.Unmodelled -> ExpansionRefusal.UnmodelledCadence(rawType)
    is Cadence.Weekly -> ExpansionRefusal.UnplaceableWeekday(days)
    is Cadence.Monthly -> on?.let { ExpansionRefusal.UnplaceableMonthlyAnchor(it) }
        ?: ExpansionRefusal.MonthlyWithoutAnchor
    // Every remaining cadence produces a rule, so this is unreachable by construction.
    is Cadence.Daily, is Cadence.EveryNDays, is Cadence.Yearly -> ExpansionRefusal.CustomCadence
}

// ── Calendar helpers ──────────────────────────────────────────────────────────────────────────────

/**
 * The first of each period month, from [anchorDate]'s month, stepping by [interval].
 *
 * Stepped from the **first** of the month so `plus(n, MONTH)`'s clamping can never fire, and the day is
 * re-attached afterwards. A period whose day does not exist yields no firing but still consumes its
 * interval slot — measured: `BYMONTHDAY=31` with `INTERVAL=2` from January fires Jan, Mar, May, Jul,
 * then *January* of the next year, because September and November have no 31st and are skipped without
 * shifting the phase.
 */
private fun monthStarts(anchorDate: LocalDate, horizon: LocalDate, interval: Int): Sequence<LocalDate> {
    val step = interval.coerceAtLeast(1)
    val first = LocalDate(anchorDate.year, anchorDate.month, 1)
    return generateSequence(first) { it.plus(step, DateTimeUnit.MONTH) }.takeWhile { it <= horizon }
}

/** This month's [day], or `null` when the month is too short — **skip, never clamp**. */
private fun LocalDate.withDayOfMonth(day: Int): LocalDate? = localDateOrNull(year, month.number, day)

/**
 * The [nth] [weekday] of this month (negative counts back from the month's end), or `null` when the
 * month has no such day — a fifth Thursday exists in only five months of 2026.
 */
private fun LocalDate.nthWeekdayOfMonth(nth: Int, weekday: DayOfWeek): LocalDate? {
    if (nth == 0) return null
    val first = LocalDate(year, month, 1)
    val last = first.plus(1, DateTimeUnit.MONTH).addDays(-1)
    val candidate = if (nth > 0) {
        val offset = (weekday.isoDayNumber - first.dayOfWeek.isoDayNumber + 7) % 7
        first.addDays(offset + (nth - 1) * 7)
    } else {
        val offset = (last.dayOfWeek.isoDayNumber - weekday.isoDayNumber + 7) % 7
        last.addDays(-offset - (-nth - 1) * 7)
    }
    return candidate.takeIf { it >= first && it <= last }
}

private fun localDateOrNull(year: Int, month: Int, day: Int): LocalDate? =
    runCatching { LocalDate(year, month, day) }.getOrNull()

// ── Zone resolution ───────────────────────────────────────────────────────────────────────────────

/**
 * Every instant this wall time has in [zone] — chrono's `LocalResult`, reproduced: **empty** in a
 * spring-forward gap, **one** normally, **two** (earlier first) in a fall-back fold.
 *
 * `kotlinx.datetime` has no `LocalResult`, and its `toInstant` collapses all three cases to a single
 * answer — so the distinction is rebuilt here by probing the zone's offset on either side of the wall
 * time and keeping each candidate that is genuinely its own offset's instant. That distinction is
 * load-bearing three times over: `DTSTART`/`EXDATE` demand exactly one, an ordinary firing falls back
 * to midnight-plus-elapsed when there is not exactly one, and a firing with no resolvable midnight is
 * dropped from the grid altogether.
 */
private fun LocalDateTime.candidatesIn(zone: TimeZone): List<Instant> {
    val probe = toInstant(UtcOffset.ZERO)
    val offsets = linkedSetOf(
        zone.offsetAt(probe - PROBE_SLACK),
        zone.offsetAt(probe),
        zone.offsetAt(probe + PROBE_SLACK),
    )
    val out = ArrayList<Instant>(2)
    for (offset in offsets) {
        val candidate = toInstant(offset)
        if (zone.offsetAt(candidate) == offset && candidate !in out) out += candidate
    }
    out.sort()
    return out
}

/**
 * `rrule`'s `add_time_to_date` (`rrule-0.14.0/src/iter/utils.rs:76-87`), reproduced exactly: the wall
 * time if it is unambiguous, else local midnight plus the time as an elapsed duration, else `null` —
 * the firing does not exist and is not counted.
 */
private fun resolveSlot(date: LocalDate, time: LocalTime, zone: TimeZone): Instant? {
    LocalDateTime(date, time).candidatesIn(zone).singleOrNull()?.let { return it }
    val midnight = LocalDateTime(date, LocalTime(0, 0)).candidatesIn(zone).singleOrNull() ?: return null
    return midnight + time.toSecondOfDay().seconds
}

/**
 * The instant a [RecurrenceBound.OnDate] bound becomes — the backend's
 * `time::compute_occurrence_complete_by(date, None, tz)` (`backend/src/time.rs:83-108`), which is the
 * canonical inclusive end-of-day sentinel and the *only* DST strategy the server documents: 23:59:59 on
 * the local day, walked forward a minute at a time out of a spring-forward gap, taking the earlier
 * instant on a fall-back fold.
 *
 * `UNTIL` is **inclusive** (`recurrence.rs:307`), so a firing landing exactly on this instant is kept —
 * which is what keeps the whole `OnDate` day regardless of the reader's UTC offset sign.
 */
private fun inclusiveEndOfDayInstant(date: LocalDate, zone: TimeZone): Instant {
    val base = LocalDateTime(date, LocalTime(23, 59, 59))
    for (offsetMinutes in 0..MAX_GAP_SLACK_MINUTES) {
        val candidate = base.plusMinutes(offsetMinutes)
        candidate.candidatesIn(zone).firstOrNull()?.let { return it }
    }
    // Every IANA zone has a valid local instant within four hours of any wall time; the backend panics
    // here for the same reason. Falling back on the naive UTC reading keeps this function total.
    return base.toInstant(UtcOffset.ZERO)
}

/** Naive wall-clock arithmetic — deliberately *not* zone-aware; this walks a local time, not an instant. */
private fun LocalDateTime.plusMinutes(minutes: Int): LocalDateTime =
    toInstant(UtcOffset.ZERO).plus(minutes.minutes).toLocalDateTime(TimeZone.UTC)

/** The zone-offset probe window. No IANA zone has two transitions within two days of each other. */
private val PROBE_SLACK = 2.days

/** The backend's own slack when walking out of a spring-forward gap (`time.rs:96`). */
private const val MAX_GAP_SLACK_MINUTES = 4 * 60

/**
 * Parity with `defernodate`'s `rrule_set…all(u16::MAX)` (`expand.rs:61-65`), which collects at most
 * this many firings and then stops. Reachable only by a window spanning ~180 years of a daily rule.
 */
private const val MAX_FIRINGS = 65_535
