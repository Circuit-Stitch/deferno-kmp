package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceCursor
import com.circuitstitch.deferno.core.model.RelativeDay
import com.circuitstitch.deferno.core.model.recurrenceCursor
import com.circuitstitch.deferno.core.model.wireWeekday
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * The **one** normalisation of a recurring [Item]'s rule into the pieces a row subtitle is built from
 * (#384) — "Weekly on Mon, Wed · until Jun 14, 2026 · Next: Tomorrow" — shared by all four platforms.
 *
 * It lives here, in the slice's Compose-free logic module, for one reason: this module is exported into
 * **both** Apple frameworks (`export(project(":feature:tasks"))` in `app/iosApp` and `app/macosApp`) and
 * is also the module `:feature:tasks:ui` renders from. So Android, desktop, iOS and macOS all read the
 * same normalisation from the same object file. The alternative — the shape this replaced — was the rules
 * written three times: once in the Compose `recurrenceSummary` and once in each Apple app's own
 * `Bridge.kt`, the two Apple copies kept in step by a comment saying they must be. Nothing enforced it,
 * neither Apple app has a test target, and the `app/` entry points are outside the Kover gate, so the
 * two largest copies were also the two nobody measured. One copy in a feature slice is measured by
 * construction.
 *
 * **Normalising is the point, not projecting.** Every rule the platforms must agree on is applied here
 * and cannot be re-litigated downstream, which is why the renderers below hold no arithmetic at all:
 *
 * 1. **`EveryNDays(1)` folds into [CadenceReading.Daily]** — it means exactly that, and the locales
 *    disagree on whether "Every 1 day" is even grammatical (de drops the numeral outright, so its plural
 *    has no well-formed `one` arm to land on). A stride below 1 folds here too: it cannot be rendered as
 *    a stride at all, and "Daily" is the least-wrong reading of a rule that fires without one.
 * 2. **Intervals are floored at 1**, so no renderer can print "Every 0 months". An interval of *exactly*
 *    1 is deliberately left alone: `tasks_cadence_monthly`/`_yearly` put the plain adverb in their own
 *    `one` arm, so the catalog already does that normalising and doing it twice is the same rule written
 *    twice.
 * 3. **Weekday tokens become ISO day numbers, canonicalised.** The wire ships `chrono::Weekday`'s Display
 *    form (`"Mon"`..`"Sun"`) — English regardless of locale — so a token this build cannot place is
 *    dropped (a rule it cannot fully read still round-trips the cache verbatim, #382: it must degrade to
 *    a shorter list, never take the row down), duplicates collapse, and what survives is sorted into week
 *    order. `["Wed", "Mon"]` therefore reads "Mon, Wed": the row states which days a rule fires on, not
 *    the order the server happened to serialize them in.
 *
 * The day *names* are emphatically **not** resolved here. ISO numbers cross to each platform's own CLDR
 * source (`shortWeekdayLabels` on Compose, `Calendar.shortWeekdaySymbols` on Apple) because CLAUDE.md
 * forbids a hand-rolled per-locale weekday table and Kotlin/Native has no `java.time` to build one from.
 * Numbers also delete the token→index lookup each renderer used to carry its own copy of.
 */
data class RecurrenceReading(
    val cadence: CadenceReading,
    /** The rule's upper bound, or `null` for [RecurrenceBound.Never] — "nothing to render", not "unbounded". */
    val bound: RecurrenceBound?,
    /** Where the series has walked to, read against a day — see [RecurrenceCursor]. */
    val cursor: RecurrenceCursor,
)

/**
 * A [Cadence] with the three rules above already applied — the shape a renderer can `when` over with no
 * arithmetic of its own. One arm per catalog phrase, which is why it is not simply [Cadence]: that type
 * models the *wire*, and carries three parameters ([Cadence.Monthly.on], [Cadence.Yearly.month]/`day`,
 * [Cadence.Custom.rrule]) that deliberately never reach a row. The first needs ordinal-plus-weekday
 * grammar that de/es/hi/pt have no key family to copy and the webui does not render either; the last is
 * machine text. A one-line row says how **often** a thing repeats — #383's detail surface is where
 * exactly *when* belongs. Dropping them here rather than in each renderer is what stops one platform
 * quietly growing a paraphrase the other three do not have.
 */
sealed interface CadenceReading {

    /** Every day — including a stride-of-one [Cadence.EveryNDays], folded in. */
    data object Daily : CadenceReading

    /** Every [n] days, `n >= 2`. */
    data class EveryNDays(val n: Int) : CadenceReading

    /**
     * The given weekdays as **ISO day numbers** (1 = Monday … 7 = Sunday), deduped and in week order.
     * Empty when the rule named no day this build could place — which is exactly the day-less "Weekly"
     * reading `tasks_cadence_weekly` exists for, not an error.
     */
    data class Weekly(val isoDays: List<Int>) : CadenceReading

    /** Every [interval] months, `interval >= 1`; the wire's monthly anchor is not carried (see the KDoc). */
    data class Monthly(val interval: Int) : CadenceReading

    /** Every [interval] years, `interval >= 1`; the wire's month/day are not carried (see the KDoc). */
    data class Yearly(val interval: Int) : CadenceReading

    /** A raw RFC-5545 rule. The rrule itself is not carried — a row renders "Custom schedule", never it. */
    data object Custom : CadenceReading

    /**
     * A cadence this build cannot model ([Cadence.Unmodelled], #382). The wire token is not carried: it
     * survives in the *model* so an unrenderable rule round-trips the cache under its own name, not so a
     * user reads a wire enum. Renders the deliberately-vague `tasks_cadence_unknown` ("Repeats") — which
     * is already a verb phrase, and is therefore the one arm the screen-reader prefix must skip.
     */
    data object Unspecified : CadenceReading
}

/**
 * The [RecurrenceReading] for this [Item], or `null` when there is nothing to say — a [Task] (no rule),
 * or a recurring definition whose rule did not survive the wire. The rule is the discriminator; the
 * cursor is only the value.
 *
 * **Call this at render time; never bake it into repository state.** `ItemRepository.observeItems()` is a
 * cold `Flow` over the local caches (ADR-0001) that only re-emits when the *database* changes, so a
 * reading computed at emit time would still claim "Tomorrow" tomorrow. Nothing about a clock tick reaches
 * that Flow.
 *
 * [today] **derives from [zone]** and callers should keep it that way — supplying only a non-device
 * [zone] must not leave "today" resolved in the device's. That is exactly the call shape #392 will use,
 * and the mismatch would silently shift the reading by a day for anyone near a date boundary. The Compose
 * caller passes `currentToday(zone)`, which applies the same coupling to the `LocalToday` test override.
 */
fun Item.recurrenceReading(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = Clock.System.todayIn(zone),
): RecurrenceReading? {
    val rule = recurrence ?: return null
    return RecurrenceReading(
        cadence = rule.cadence.reading(),
        bound = rule.bound.takeIf { it != RecurrenceBound.Never },
        cursor = recurrenceCursor(zone, today),
    )
}

private fun Cadence.reading(): CadenceReading = when (this) {
    Cadence.Daily -> CadenceReading.Daily
    // Rule 1: a stride of one (or a nonsensical zero) IS "Daily".
    is Cadence.EveryNDays -> if (n <= 1) CadenceReading.Daily else CadenceReading.EveryNDays(n)
    is Cadence.Weekly -> CadenceReading.Weekly(days.toIsoDayNumbers())
    // Rule 2: floor the interval; 1 stays 1, because the catalog's `one` arm is what drops the numeral.
    is Cadence.Monthly -> CadenceReading.Monthly(interval.coerceAtLeast(1))
    is Cadence.Yearly -> CadenceReading.Yearly(interval.coerceAtLeast(1))
    is Cadence.Custom -> CadenceReading.Custom
    is Cadence.Unmodelled -> CadenceReading.Unspecified
}

/**
 * Rule 3: unplaceable tokens dropped, duplicates collapsed, survivors in week order as ISO 1..7.
 *
 * The token table itself lives on [wireWeekday] in `core/model`, beside the [Cadence] KDoc that
 * declares the vocabulary — this file kept its own copy until the [Occurrence grid] expander needed
 * the same mapping (#401), and two independent tables of one wire vocabulary is the second
 * specification this reading exists to prevent.
 *
 * Note the differing failure policy, which is deliberate: a *display* line drops what it cannot place
 * and still says something useful, while the expander refuses the whole grid rather than ship a
 * schedule quietly missing a day (ADR-0053 — absent, not empty).
 */
private fun List<String>.toIsoDayNumbers(): List<Int> =
    mapNotNull { wireWeekday(it)?.isoDayNumber }
        .distinct()
        .sorted()

/**
 * The flat, token-shaped projection of a [RecurrenceReading] for the **SwiftUI** twins — the typed pieces
 * `L.recurrenceLine` assembles into a localized line, exactly as [Item]'s Trail rows already cross as a
 * single `HistoryLine`. Built by [recurrenceLineTokens]; `null` there means "render nothing".
 *
 * Flat and stringly-typed because Swift cannot take a sealed hierarchy apart: a bridged sealed type
 * arrives as an opaque class it can neither `==` nor pattern-match, so the house idiom is a stable String
 * token the SwiftUI `L` maps to a catalog key (as `journeyLabelToken`/`taskDueRelativeToken` already do).
 * The tokens are an *encoding*, not a second model — [RecurrenceReading] above is the model, and this is
 * derived from it, so a new cadence arm is a compile error here rather than a silent divergence.
 *
 * **One value, not eight accessors.** This crosses the bridge once per row. The shape it replaced was
 * eight paired getters (`itemCadenceToken` + `itemCadenceCount` + …) that each re-derived the cadence and
 * the cursor from scratch — three and two times respectively, per row, per frame in a scrolling tree —
 * and encoded "this arm carries no number" as a returned `0`, including a `0` epoch-day that decodes to a
 * real 1970-01-01. That invariant was held by a comment across a language boundary with no test. Here the
 * counts are **nullable**: an arm that carries no number says so in the type, and Swift cannot read one
 * without unwrapping it.
 */
data class RecurrenceLineTokens(
    /** `DAILY | EVERY_N_DAYS | WEEKLY | MONTHLY | YEARLY | CUSTOM | UNSPECIFIED`. Always present. */
    val cadence: String,
    /** The stride/interval the cadence plural agrees on; `null` for the arms that carry no number. */
    val cadenceCount: Int?,
    /** ISO day numbers (1 = Monday … 7 = Sunday), canonical; empty unless [cadence] is `WEEKLY`. */
    val weekdays: List<Int>,
    /** `ON_DATE | AFTER_COUNT`, or `null` for the open-ended default, which renders nothing at all. */
    val bound: String?,
    /** The firing count of an `AFTER_COUNT` bound (the wire's `COUNT`); `null` otherwise. */
    val boundCount: Int?,
    /**
     * An `ON_DATE` bound (the wire's `UNTIL`) as **epoch days**; `null` otherwise.
     *
     * Days, not the epoch *seconds* every picker seam crosses on, because this is a calendar day with no
     * clock and no zone: seconds would force Swift to pick a zone to read them in, and every user west of
     * Greenwich would see the day before. Swift reconstructs UTC midnight and formats in UTC.
     */
    val boundEpochDays: Int?,
    /**
     * `EXHAUSTED | TODAY | TOMORROW | YESTERDAY | DAYS_AWAY | DAYS_AGO`, or `null` for
     * [RecurrenceCursor.NoCursor] — a Task, or an **Archived** definition whose stale cursor the reading
     * deliberately refuses to believe. The five relative-day arms are the same tokens
     * `taskDueRelativeToken` emits, so Swift maps them through the one `L.relativeDay` and the
     * `tasks_detail_due_*` keys it already owns.
     */
    val cursor: String?,
    /** The day count for the cursor's `DAYS_AWAY`/`DAYS_AGO` plural; `null` for the other arms. */
    val cursorCount: Int?,
)

/**
 * The [RecurrenceLineTokens] for [item] — the single seam the SwiftUI twins call, once per row.
 *
 * Derived on every call rather than carried on the row, for the reason [recurrenceReading] gives: the
 * cursor is a reading against *today*, and the Flow behind the Item tree only re-emits when the database
 * changes. A value baked at emit time would still be claiming "Tomorrow" tomorrow.
 */
fun recurrenceLineTokens(item: Item): RecurrenceLineTokens? =
    recurrenceLineTokens(item, TimeZone.currentSystemDefault())

/**
 * [recurrenceLineTokens] with the reading's day inputs exposed, so a test can pin them. Not the Swift
 * seam: Kotlin default arguments do not survive into an ObjC header as defaults, and SwiftUI wants the
 * one-argument call. Kept as an overload rather than defaulted parameters for exactly that reason.
 */
internal fun recurrenceLineTokens(
    item: Item,
    zone: TimeZone,
    today: LocalDate = Clock.System.todayIn(zone),
): RecurrenceLineTokens? {
    val reading = item.recurrenceReading(zone, today) ?: return null
    val bound = reading.bound
    val cursorDay = (reading.cursor as? RecurrenceCursor.DueOn)?.day
    return RecurrenceLineTokens(
        cadence = when (reading.cadence) {
            CadenceReading.Daily -> "DAILY"
            is CadenceReading.EveryNDays -> "EVERY_N_DAYS"
            is CadenceReading.Weekly -> "WEEKLY"
            is CadenceReading.Monthly -> "MONTHLY"
            is CadenceReading.Yearly -> "YEARLY"
            CadenceReading.Custom -> "CUSTOM"
            CadenceReading.Unspecified -> "UNSPECIFIED"
        },
        cadenceCount = when (val cadence = reading.cadence) {
            is CadenceReading.EveryNDays -> cadence.n
            is CadenceReading.Monthly -> cadence.interval
            is CadenceReading.Yearly -> cadence.interval
            else -> null
        },
        weekdays = (reading.cadence as? CadenceReading.Weekly)?.isoDays.orEmpty(),
        bound = when (bound) {
            null -> null
            is RecurrenceBound.OnDate -> "ON_DATE"
            is RecurrenceBound.AfterCount -> "AFTER_COUNT"
            RecurrenceBound.Never -> null // unreachable: `reading.bound` nulls Never out. Kept exhaustive.
        },
        boundCount = (bound as? RecurrenceBound.AfterCount)?.n,
        boundEpochDays = (bound as? RecurrenceBound.OnDate)?.date?.toEpochDays()?.toInt(),
        cursor = when (val cursor = reading.cursor) {
            RecurrenceCursor.NoCursor -> null
            RecurrenceCursor.Exhausted -> "EXHAUSTED"
            is RecurrenceCursor.DueOn -> when (cursor.day) {
                RelativeDay.Today -> "TODAY"
                RelativeDay.Tomorrow -> "TOMORROW"
                RelativeDay.Yesterday -> "YESTERDAY"
                is RelativeDay.DaysAway -> "DAYS_AWAY"
                is RelativeDay.DaysAgo -> "DAYS_AGO"
            }
        },
        cursorCount = when (cursorDay) {
            is RelativeDay.DaysAway -> cursorDay.days
            is RelativeDay.DaysAgo -> cursorDay.days
            else -> null
        },
    )
}
