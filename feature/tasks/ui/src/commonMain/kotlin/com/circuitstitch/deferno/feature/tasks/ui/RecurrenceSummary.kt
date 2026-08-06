package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.runtime.Composable
import com.circuitstitch.deferno.core.designsystem.format.currentToday
import com.circuitstitch.deferno.core.designsystem.format.formatDate
import com.circuitstitch.deferno.core.designsystem.format.shortWeekdayLabels
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_device_date_pattern
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_custom
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_daily
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_every_n_days
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_month_day_pattern
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_monthly
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_monthly_every_n_on_day
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_monthly_every_n_on_weekday
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_monthly_on_day
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_monthly_on_weekday
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_nth_fifth
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_nth_first
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_nth_fourth
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_nth_last
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_nth_second
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_nth_third
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_times
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_unknown
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_until
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_weekday_separator
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_weekly
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_weekly_on
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_with_bound
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_yearly
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_yearly_every_n_on
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_yearly_on
import com.circuitstitch.deferno.core.designsystem.resources.tasks_recurrence_a11y_prefix
import com.circuitstitch.deferno.core.designsystem.resources.tasks_recurrence_next_due
import com.circuitstitch.deferno.core.designsystem.resources.tasks_recurrence_series_ended
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceCursor
import com.circuitstitch.deferno.core.model.wireWeekday
import com.circuitstitch.deferno.feature.tasks.CadenceReading
import com.circuitstitch.deferno.feature.tasks.recurrenceReading
import java.util.Locale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The one extra subtitle line a **recurring** Item-tree row wears under its title (#384) — "Weekly on
 * Mon, Wed · until Jun 14, 2026 · Next: Tomorrow". Before this, #275 (due next month) and #277 (overdue
 * since July) rendered as the same bare title; the projection has carried the facts since wave 1, this
 * turns them into a phrase.
 *
 * Two fields because the line is **not** read aloud the way it is written. [text] is the visual line;
 * [a11yLabel] is the same information with the cadence wrapped in `tasks_recurrence_a11y_prefix`
 * ("Repeats %1$s"), because "Weekly on Mon, Wed" alone is a fragment a screen reader cannot place. The
 * View sets [a11yLabel] with `clearAndSetSemantics`, which *replaces* the visible text — so [a11yLabel]
 * must repeat everything [text] says, and it is also what saves the announcement when the visual line
 * ellipsizes on a narrow row (a `MonoMeta` is single-line).
 *
 * Built by [recurrenceSummary]; `null` there means "render nothing", which is the whole story for a Task.
 */
internal data class RecurrenceSummary(val text: String, val a11yLabel: String)

/**
 * The [RecurrenceSummary] for [item], or `null` when there is nothing to say — a Task (no rule), or a
 * recurring definition whose rule did not survive the wire. Three parts, each independently omissible,
 * joined by `tasks_cadence_with_bound` ("%1$s · %2$s"): the **cadence** (always present once a rule is),
 * the **bound** ("until 14 Jun" / "10 times", nothing at all when open-ended), and the **next due**
 * reading ("Next: Tomorrow", or "Series ended" for an exhausted series).
 *
 * **This function holds no rules of its own — only phrasing.** Every decision the four platforms must
 * agree on (the `EveryNDays(1)` fold, the interval floor, which weekday tokens survive and in what order,
 * which wire parameters never reach a row) is applied by
 * [recurrenceReading][com.circuitstitch.deferno.feature.tasks.recurrenceReading], which the two SwiftUI
 * twins read through the same object file. Nothing here should reintroduce arithmetic: if a rule needs
 * changing it changes there, once, for all four platforms.
 *
 * [zone] is the only date input, deliberately. "Today" derives from it via [currentToday], so a caller
 * cannot resolve the cursor's instant in one zone and "today" in another — a silent one-day slip near a
 * date boundary, and exactly the call shape #392 will use once an account zone exists. A test pins the
 * day with `CompositionLocalProvider(LocalToday provides …)`, which is also what stops the screenshot
 * goldens drifting daily. **Never call `Clock.System.now()` from a Composable** — and never precompute
 * the reading upstream either: `observeItems()` re-emits on a database write, never on a clock tick, so a
 * baked reading would still claim "Tomorrow" tomorrow (see `RecurrenceCursor`'s KDoc).
 *
 * The device zone is **not** guaranteed to be the account zone the server scheduled against — #392 tracks
 * that divergence for the whole app; this follows the existing convention rather than pre-empting it.
 */
@Composable
internal fun recurrenceSummary(
    item: Item,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    locale: Locale = Locale.getDefault(),
): RecurrenceSummary? {
    val reading = item.recurrenceReading(zone, currentToday(zone)) ?: return null

    val cadence = cadencePhrase(reading.cadence, locale)
    // RENDERER RULE (tasks_cadence_unknown / tasks_recurrence_a11y_prefix): the Unspecified arm's string
    // IS the verb phrase the prefix would add, in all five locales — wrapping it speaks "Repeats Repeats".
    // Every other arm is a bare adverbial ("Weekly on Mon, Wed") that needs the verb to make sense aloud.
    val spokenCadence = if (reading.cadence == CadenceReading.Unspecified) {
        cadence
    } else {
        stringResource(Res.string.tasks_recurrence_a11y_prefix, cadence)
    }

    val bound = boundPhrase(reading.bound, locale)
    val nextDue = nextDuePhrase(reading.cursor)

    return RecurrenceSummary(
        text = joinedWithSeparator(joinedWithSeparator(cadence, bound), nextDue),
        a11yLabel = joinedWithSeparator(joinedWithSeparator(spokenCadence, bound), nextDue),
    )
}

/**
 * The **detail's** rule line (#383) — the cadence and its bound, with the monthly/yearly ANCHOR the tree
 * row deliberately drops. `null` when there is nothing to say (a Task, or a rule that did not survive the
 * wire), exactly as [recurrenceSummary] uses `null`.
 *
 * Two things make this a peer of [recurrenceSummary] rather than a flag on it:
 *
 * 1. **It carries no next-due clause.** The detail gives that its own `NEXT DUE` row, so folding it in
 *    here would print the same reading twice on one screen.
 * 2. **It reaches past [CadenceReading] for the anchor.**
 *    [RecurrenceReading][com.circuitstitch.deferno.feature.tasks.RecurrenceReading]'s KDoc (lines 61-70)
 *    drops [Cadence.Monthly.on] and [Cadence.Yearly]'s month/day on purpose — "a one-line row says how
 *    **often** a thing repeats — #383's detail surface is where exactly *when* belongs" — and names this
 *    surface as their owner. So the anchor is read from the wire [Cadence] while the *normalisation*
 *    (the floored interval, the folds) still comes from the shared reading: this adds a clause, it does
 *    not re-derive a cadence.
 *
 * Anything the anchor cannot be read from — a nulled-out monthly `on`, an `nth` outside its closed set, a
 * weekday token this build cannot place, an impossible month/day pair — falls back to the plain cadence
 * phrase. Saying less is right where a fuller sentence would have to be invented; the same posture
 * [cadencePhrase]'s empty-weekday arm already takes.
 */
@Composable
internal fun recurrenceRulePhrase(
    item: Item,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    val cadence = item.recurrence?.cadence ?: return null
    val reading = item.recurrenceReading(zone, currentToday(zone)) ?: return null
    return joinedWithSeparator(
        anchoredCadencePhrase(cadence, reading.cadence, locale),
        boundPhrase(reading.bound, locale),
    )
}

/**
 * [cadencePhrase] with the wire [cadence]'s anchor folded in where there is one — a whole sentence per
 * arm, never the cadence with a suffix glued on (the catalog says so at `tasks_cadence_monthly_on_weekday`:
 * "Every 3 months on the second Wed" is one clause in every locale, and only the translator can shape it).
 *
 * [reading] supplies the interval, so the shared normalisation (floored at 1) is the one that reaches the
 * plural — the raw [Cadence.Monthly.interval] is not floored and could print "Every 0 months".
 */
@Composable
private fun anchoredCadencePhrase(cadence: Cadence, reading: CadenceReading, locale: Locale): String {
    val anchored = when {
        cadence is Cadence.Monthly && reading is CadenceReading.Monthly ->
            monthlyAnchorPhrase(cadence.on, reading.interval, locale)
        cadence is Cadence.Yearly && reading is CadenceReading.Yearly ->
            yearlyAnchorPhrase(cadence.month, cadence.day, reading.interval, locale)
        else -> null
    }
    return anchored ?: cadencePhrase(reading, locale)
}

/**
 * "Monthly on the second Wed" / "Every 3 months on day 15", or `null` when the anchor cannot be read —
 * which includes a rule that simply has none ([Cadence.Monthly.on] is nullable because an unreadable
 * anchor still leaves a usable monthly rule).
 *
 * The `interval == 1` split is the catalog's own RENDERER RULE: the plain adverb already says "every
 * month", so the `every_n` plurals' `one` arm is unreachable and exists only to be well-formed.
 */
@Composable
private fun monthlyAnchorPhrase(anchor: MonthlyAnchor?, interval: Int, locale: Locale): String? = when (anchor) {
    null -> null
    is MonthlyAnchor.DayOfMonth ->
        if (interval == 1) {
            stringResource(Res.string.tasks_cadence_monthly_on_day, anchor.day)
        } else {
            pluralStringResource(
                Res.plurals.tasks_cadence_monthly_every_n_on_day,
                interval,
                interval,
                anchor.day,
            )
        }
    is MonthlyAnchor.NthWeekday -> {
        val ordinal = nthOrdinal(anchor.nth)
        // The wire's `chrono::Weekday` token → CLDR, the same crossing the weekly arm makes; an
        // unplaceable token drops the whole clause rather than the day (#382's degrade-don't-die posture).
        val weekday = wireWeekday(anchor.weekday)?.let { weekdayLabel(it.isoDayNumber, locale) }
        when {
            ordinal == null || weekday == null -> null
            interval == 1 -> stringResource(Res.string.tasks_cadence_monthly_on_weekday, ordinal, weekday)
            else -> pluralStringResource(
                Res.plurals.tasks_cadence_monthly_every_n_on_weekday,
                interval,
                interval,
                ordinal,
                weekday,
            )
        }
    }
}

/**
 * [MonthlyAnchor.NthWeekday.nth] as its localized ordinal word, or `null` for a value outside the closed
 * set the model declares (`1..5`, or `-1` for "last").
 *
 * Six fixed keys and **not** a CLDR ordinal API: these are positions in a month, not counts, and each is
 * rendered only inside `tasks_cadence_monthly_on_weekday`/`_every_n_on_weekday` so a locale can inflect it
 * for that frame (es apocopates before a masculine noun, de takes the dative after "am"). `-1` is "last",
 * never "fifth" — the rule takes the final matching weekday of each month, which is the fourth in most.
 */
@Composable
private fun nthOrdinal(nth: Int): String? = when (nth) {
    1 -> stringResource(Res.string.tasks_cadence_nth_first)
    2 -> stringResource(Res.string.tasks_cadence_nth_second)
    3 -> stringResource(Res.string.tasks_cadence_nth_third)
    4 -> stringResource(Res.string.tasks_cadence_nth_fourth)
    5 -> stringResource(Res.string.tasks_cadence_nth_fifth)
    -1 -> stringResource(Res.string.tasks_cadence_nth_last)
    else -> null
}

/**
 * "Yearly on 14 June" / "Every 2 years on June 14", or `null` when [month]/[day] are not a real calendar
 * day (the wire types them as plain integers, so `(2, 31)` is representable and must not crash a detail).
 *
 * The month+day goes through `tasks_cadence_month_day_pattern`, a java.time pattern supplied per locale, so
 * the FIELD ORDER localizes ("June 14" vs "14. Juni") inside a sentence the translator never has to
 * reorder — and the month name comes from the JDK's CLDR data rather than a hand-rolled table (CLAUDE.md).
 */
@Composable
private fun yearlyAnchorPhrase(month: Int, day: Int, interval: Int, locale: Locale): String? {
    // A leap year, so 29 February is a representable anchor. The year is not in the pattern, so which one
    // it is never reaches the screen.
    val anchor = runCatching { LocalDate(LeapYearForAnchor, month, day) }.getOrNull() ?: return null
    val monthDay = formatDate(anchor, stringResource(Res.string.tasks_cadence_month_day_pattern), locale)
    return if (interval == 1) {
        stringResource(Res.string.tasks_cadence_yearly_on, monthDay)
    } else {
        pluralStringResource(Res.plurals.tasks_cadence_yearly_every_n_on, interval, interval, monthDay)
    }
}

private const val LeapYearForAnchor = 2024

/**
 * Appends [tail] to [head] with the catalog's " · " joiner, or returns [head] alone when there is no
 * tail. `tasks_cadence_with_bound` is documented as the cadence↔bound joiner and is reused verbatim for
 * the cadence↔next-due join: it is the only "%1$s · %2$s" template in the catalog, and the two joins are
 * the same typographic act. If a locale ever wants them to differ, that is a new key — not a hand-written
 * middot here (CLAUDE.md: no hardcoded user-facing strings).
 */
@Composable
private fun joinedWithSeparator(head: String, tail: String?): String =
    if (tail == null) head else stringResource(Res.string.tasks_cadence_with_bound, head, tail)

/**
 * "How often does this repeat" — one arm per `CadenceReading` variant, and nothing but a catalog lookup
 * in each. The normalisation that makes that possible (a stride of one is Daily, an interval is at least
 * 1, a weekday list is canonical or empty) already happened in the shared reading; the `one` arms of
 * `tasks_cadence_monthly`/`_yearly` drop the numeral, so an interval of exactly 1 needs no arm here
 * either.
 *
 * An empty weekday list falls back to the bare adverb: "Weekly on " with a dangling separator is worse
 * than saying less, and an unreadable day token is survivable data (#382) that must not take the row down.
 */
@Composable
private fun cadencePhrase(cadence: CadenceReading, locale: Locale): String = when (cadence) {
    CadenceReading.Daily -> stringResource(Res.string.tasks_cadence_daily)
    is CadenceReading.EveryNDays ->
        pluralStringResource(Res.plurals.tasks_cadence_every_n_days, cadence.n, cadence.n)
    is CadenceReading.Weekly ->
        if (cadence.isoDays.isEmpty()) {
            stringResource(Res.string.tasks_cadence_weekly)
        } else {
            // The separator's TRAILING SPACE is part of the catalog value — don't add one here.
            stringResource(
                Res.string.tasks_cadence_weekly_on,
                weekdayLabels(cadence.isoDays, locale)
                    .joinToString(stringResource(Res.string.tasks_cadence_weekday_separator)),
            )
        }
    is CadenceReading.Monthly -> intervalPhrase(Res.plurals.tasks_cadence_monthly, cadence.interval)
    is CadenceReading.Yearly -> intervalPhrase(Res.plurals.tasks_cadence_yearly, cadence.interval)
    CadenceReading.Custom -> stringResource(Res.string.tasks_cadence_custom)
    CadenceReading.Unspecified -> stringResource(Res.string.tasks_cadence_unknown)
}

/** The shared `Monthly`/`Yearly` shape: the interval as both the plural quantity and its lone argument. */
@Composable
private fun intervalPhrase(plural: PluralStringResource, interval: Int): String =
    pluralStringResource(plural, interval, interval)

/**
 * ISO day numbers (1 = Monday … 7 = Sunday) to localized short labels via CLDR — `shortWeekdayLabels` is
 * `java.time.DayOfWeek.entries`, MONDAY..SUNDAY, so it is index-aligned with ISO minus one. CLAUDE.md
 * forbids a hand-rolled per-locale weekday table, and this is the seam that keeps that promise; the two
 * Apple twins index `Calendar.shortWeekdaySymbols` off the very same numbers.
 */
@Composable
private fun weekdayLabels(isoDays: List<Int>, locale: Locale): List<String> {
    val labels = shortWeekdayLabels(locale)
    return isoDays.map { labels[it - 1] }
}

/**
 * One ISO day number's localized short label — [weekdayLabels] for the single day a monthly `NthWeekday`
 * anchor names. Short, not full-width, because that is what the catalog's own renderer note pins for
 * `tasks_cadence_monthly_on_weekday`'s `%2$s` — and it is what the two Apple twins read there
 * (`Calendar.shortWeekdaySymbols`), so the four platforms say the same words.
 */
@Composable
private fun weekdayLabel(isoDay: Int, locale: Locale): String = shortWeekdayLabels(locale)[isoDay - 1]

/**
 * The upper bound as a trailing clause, or `null` when the rule is open-ended — the *default* and by far
 * the common case ("the trash goes out every Tuesday", forever), so it must add nothing at all rather
 * than an "ongoing" word the catalog does not have. [RecurrenceBound.Never] never actually arrives: the
 * shared reading nulls it out, which is that statement made once instead of once per renderer.
 *
 * The date reuses `settings_security_device_date_pattern` ("MMM d, yyyy"), as the Task detail's
 * target-date cell already does — the year earns its place on an `UNTIL` that can sit years out, and
 * CLAUDE.md routes every date through a per-locale pattern resource rather than a hand-built one.
 */
@Composable
private fun boundPhrase(bound: RecurrenceBound?, locale: Locale): String? = when (bound) {
    null, RecurrenceBound.Never -> null
    is RecurrenceBound.OnDate -> stringResource(
        Res.string.tasks_cadence_until,
        formatDate(bound.date, stringResource(Res.string.settings_security_device_date_pattern), locale),
    )
    is RecurrenceBound.AfterCount -> pluralStringResource(Res.plurals.tasks_cadence_times, bound.n, bound.n)
}

/**
 * Where the series goes next, from the [RecurrenceCursor] reading. [RecurrenceCursor.NoCursor] renders
 * nothing — a Task, an unreadable rule, or an **Archived** definition, which is switched off and so has
 * no next even though the server left its cursor where it stopped. The cadence still shows for that last
 * case: an archived Habit is still a weekly Habit.
 *
 * The [RelativeDay][com.circuitstitch.deferno.core.model.RelativeDay] goes through [relativeDayText] —
 * the same `tasks_detail_due_*` mapping the Task detail's WHEN row uses (ADR-0044). A second mapping
 * would be a second thing to keep in lockstep across five locales, and the phrase is identical.
 */
@Composable
private fun nextDuePhrase(cursor: RecurrenceCursor): String? = when (cursor) {
    RecurrenceCursor.NoCursor -> null
    RecurrenceCursor.Exhausted -> stringResource(Res.string.tasks_recurrence_series_ended)
    is RecurrenceCursor.DueOn ->
        stringResource(Res.string.tasks_recurrence_next_due, relativeDayText(cursor.day))
}
