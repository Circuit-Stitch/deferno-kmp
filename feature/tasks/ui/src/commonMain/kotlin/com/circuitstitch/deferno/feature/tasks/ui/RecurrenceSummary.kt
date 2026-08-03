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
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_monthly
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_times
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_unknown
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_until
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_weekday_separator
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_weekly
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_weekly_on
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_with_bound
import com.circuitstitch.deferno.core.designsystem.resources.tasks_cadence_yearly
import com.circuitstitch.deferno.core.designsystem.resources.tasks_recurrence_a11y_prefix
import com.circuitstitch.deferno.core.designsystem.resources.tasks_recurrence_next_due
import com.circuitstitch.deferno.core.designsystem.resources.tasks_recurrence_series_ended
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceCursor
import com.circuitstitch.deferno.core.model.recurrenceCursor
import java.util.Locale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
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
 * joined by `tasks_cadence_with_bound` ("%1$s · %2$s"):
 *
 * 1. **Cadence**, from `Recurrence.cadence` — always present once a rule is. See [cadencePhrase] for the
 *    three renderer rules the string catalog records on the keys themselves.
 * 2. **Bound**, from `Recurrence.bound` — "until 14 Jun" / "10 times", nothing at all for `Never`.
 * 3. **Next due**, from [recurrenceCursor] — "Next: Tomorrow", or "Series ended" for an exhausted series.
 *
 * **Only three of the six `Cadence` parameters reach the row.** `Monthly.on` ("the 2nd Tuesday"),
 * `Yearly.month`/`day`, and `Custom.rrule` are deliberately not rendered here: the first needs
 * ordinal+weekday grammar in de/es/hi/pt with no key family to copy, and the webui does not render it
 * either. That detail belongs on #383's detail surface, where there is room to spell it out — do not
 * invent keys for it on the row.
 *
 * [today] defaults to the design system's [currentToday] rather than a clock read, so a screenshot test
 * can pin the day (`CompositionLocalProvider(LocalToday provides …)`) and the "Next: …" goldens stop
 * drifting daily. **Never call `Clock.System.now()` from a Composable** — and never precompute the
 * reading upstream either: `observeItems()` re-emits on a database write, never on a clock tick, so a
 * baked reading would still claim "Tomorrow" tomorrow (see `RecurrenceCursor`'s KDoc).
 *
 * [zone] is the device zone, which is **not** guaranteed to be the account zone the server scheduled
 * against — #392 tracks that divergence for the whole app; this follows the existing convention rather
 * than pre-empting it.
 */
@Composable
internal fun recurrenceSummary(
    item: Item,
    today: LocalDate = currentToday,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    locale: Locale = Locale.getDefault(),
): RecurrenceSummary? {
    val recurrence = item.recurrence ?: return null

    val cadence = cadencePhrase(recurrence.cadence, locale)
    // RENDERER RULE (tasks_cadence_unknown / tasks_recurrence_a11y_prefix): the Unmodelled arm's string
    // IS the verb phrase the prefix would add, in all five locales — wrapping it speaks "Repeats Repeats".
    // Every other arm is a bare adverbial ("Weekly on Mon, Wed") that needs the verb to make sense aloud.
    val spokenCadence = if (recurrence.cadence is Cadence.Unmodelled) {
        cadence
    } else {
        stringResource(Res.string.tasks_recurrence_a11y_prefix, cadence)
    }

    val bound = boundPhrase(recurrence.bound, locale)
    val nextDue = nextDuePhrase(item.recurrenceCursor(zone, today))

    return RecurrenceSummary(
        text = joinedWithSeparator(joinedWithSeparator(cadence, bound), nextDue),
        a11yLabel = joinedWithSeparator(joinedWithSeparator(spokenCadence, bound), nextDue),
    )
}

/**
 * Appends [tail] to [head] with the catalog's " · " joiner, or returns [head] alone when there is no
 * tail. `tasks_cadence_with_bound` is documented as the cadence↔bound joiner and is reused verbatim for
 * the cadence↔next-due join: it is the only "%1$s · %2$s" template in the catalog, the catalogs are
 * frozen for #384, and the two joins are the same typographic act. If a locale ever wants them to differ,
 * that is a new key — not a hand-written middot here (CLAUDE.md: no hardcoded user-facing strings).
 */
@Composable
private fun joinedWithSeparator(head: String, tail: String?): String =
    if (tail == null) head else stringResource(Res.string.tasks_cadence_with_bound, head, tail)

/**
 * "How often does this repeat" — one arm per `Cadence` variant. Three of them carry a **RENDERER RULE**
 * written on the string keys themselves, and each is a trap:
 *
 * - **`EveryNDays(1)` normalises to `tasks_cadence_daily`.** It means exactly "Daily", the plural's `one`
 *   arm is unreachable by design, and the locales disagree on whether "Every 1 day" is even grammatical
 *   (de drops the numeral outright). A stride below 1 is not a thing the backend emits; it collapses here
 *   too rather than rendering "Every 0 days".
 * - **An empty or unreadable weekly day list falls back to `tasks_cadence_weekly`.** "Weekly on " with a
 *   dangling separator is worse than saying less, and an unknown weekday token is survivable data (the
 *   rule round-trips the cache verbatim, #382) — it must not take the row down.
 * - **The `Unmodelled` arm renders `tasks_cadence_unknown` ("Repeats"), never `rawType`.** The token is
 *   carried so the rule survives the cache and the Backup file, not so a user reads a wire enum.
 *
 * `Custom` likewise renders "Custom schedule" and never the raw RFC-5545 rrule. `Monthly`/`Yearly` share
 * the "interval 1 → the plain adverb" shape through their plurals' `one` arm, which drops the numeral.
 */
@Composable
private fun cadencePhrase(cadence: Cadence, locale: Locale): String = when (cadence) {
    Cadence.Daily -> stringResource(Res.string.tasks_cadence_daily)
    is Cadence.EveryNDays ->
        if (cadence.n <= 1) {
            stringResource(Res.string.tasks_cadence_daily)
        } else {
            pluralStringResource(Res.plurals.tasks_cadence_every_n_days, cadence.n, cadence.n)
        }
    is Cadence.Weekly -> {
        val days = localizedWeekdays(cadence.days, locale)
        if (days.isEmpty()) {
            stringResource(Res.string.tasks_cadence_weekly)
        } else {
            // The separator's TRAILING SPACE is part of the catalog value — don't add one here.
            stringResource(
                Res.string.tasks_cadence_weekly_on,
                days.joinToString(stringResource(Res.string.tasks_cadence_weekday_separator)),
            )
        }
    }
    // The `one` arm drops the numeral ("Monthly"), so an interval of 1 needs no special case — only a
    // guard against a non-positive interval, which would otherwise render "Every 0 months".
    is Cadence.Monthly -> intervalPhrase(Res.plurals.tasks_cadence_monthly, cadence.interval)
    is Cadence.Yearly -> intervalPhrase(Res.plurals.tasks_cadence_yearly, cadence.interval)
    is Cadence.Custom -> stringResource(Res.string.tasks_cadence_custom)
    is Cadence.Unmodelled -> stringResource(Res.string.tasks_cadence_unknown)
}

/** The shared `Monthly`/`Yearly` shape: the interval as both the plural quantity and its lone argument. */
@Composable
private fun intervalPhrase(plural: PluralStringResource, interval: Int): String {
    val n = interval.coerceAtLeast(1)
    return pluralStringResource(plural, n, n)
}

/**
 * The upper bound as a trailing clause, or `null` for [RecurrenceBound.Never] — which is the *default*
 * and by far the common case ("the trash goes out every Tuesday", forever), so it must add nothing at
 * all rather than an "ongoing" word the catalog does not have.
 *
 * The date reuses `settings_security_device_date_pattern` ("MMM d, yyyy"), as the Task detail's target-date
 * cell already does — the year earns its place on an `UNTIL` that can sit years out, and CLAUDE.md routes
 * every date through a per-locale pattern resource rather than a hand-built one.
 */
@Composable
private fun boundPhrase(bound: RecurrenceBound, locale: Locale): String? = when (bound) {
    RecurrenceBound.Never -> null
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

/**
 * The wire's weekday tokens in ISO order — `Cadence.Weekly.days` ships `chrono::Weekday`'s Display form
 * ("Mon".."Sun"), which is **English regardless of the user's locale**. This list exists only to turn a
 * token into an index; it is never displayed.
 */
private val WireWeekdayTokens = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Maps the wire's English weekday [tokens] to localized short labels via CLDR (`shortWeekdayLabels`,
 * which is `java.time.DayOfWeek.entries` — MONDAY..SUNDAY, index-aligned with [WireWeekdayTokens]).
 * CLAUDE.md forbids a hand-rolled per-locale weekday table, and this is the seam that keeps that promise.
 *
 * An unrecognised token is **dropped, not rendered and not thrown on**: `Cadence` deliberately preserves
 * rules this build cannot fully read (#382), so a future backend's token must degrade to a shorter day
 * list — and, if it drops them all, to the bare "Weekly" fallback in [cadencePhrase]. Output is deduped
 * and put in week order, so `["Wed", "Mon"]` reads "Mon, Wed": the row states which days, not which order
 * the server happened to serialize them in.
 */
internal fun localizedWeekdays(tokens: List<String>, locale: Locale = Locale.getDefault()): List<String> {
    val labels = shortWeekdayLabels(locale)
    return tokens
        .mapNotNull { token ->
            WireWeekdayTokens.indexOfFirst { it.equals(token, ignoreCase = true) }.takeIf { it >= 0 }
        }
        .distinct()
        .sorted()
        .map(labels::get)
}
