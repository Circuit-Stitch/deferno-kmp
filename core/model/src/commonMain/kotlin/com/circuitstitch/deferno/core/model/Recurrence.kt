package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate

/**
 * The recurrence rule a recurring definition (a [Habit] / [Chore] / [Event]) fires on (CONTEXT.md →
 * "Definition" / "Recurrence"). The clean domain projection of the wire `recurrence` object (ADR-0011
 * condense-at-edge): a **cadence** ([frequency] plus the parameters that cadence carries) and an
 * optional upper [bound].
 *
 * The wire is FLAT — the backend hand-writes `Serialize`/`Deserialize` so the cadence's own fields are
 * hoisted to the top level and only `end` is nested (`backend/src/models/recurrence.rs`). Which
 * parameters are meaningful is decided entirely by [frequency]:
 *
 * | [frequency] | wire | carries |
 * |---|---|---|
 * | [RecurrenceFrequency.Daily] | `{"type":"daily"}` | — |
 * | [RecurrenceFrequency.EveryNDays] | `{"type":"every_n_days","n":3}` | [interval] |
 * | [RecurrenceFrequency.Weekly] | `{"type":"weekly","days":["Mon","Wed"]}` | [days] |
 * | [RecurrenceFrequency.Monthly] | `{"type":"monthly","interval":2,"on":{…}}` | [interval], [monthlyAnchor] |
 * | [RecurrenceFrequency.Yearly] | `{"type":"yearly","interval":1,"month":6,"day":14}` | [interval], [month], [day] |
 * | [RecurrenceFrequency.Custom] | `{"type":"custom","rrule":"FREQ=…"}` | [rrule] |
 *
 * [interval] deliberately condenses the wire's two numeric keys — `every_n_days.n` and
 * `monthly`/`yearly`'s `interval` — into ONE domain concept: *the cycle multiplier*, whose unit the
 * [frequency] supplies ("every 3 **days**", "every 2 **months**"). They can never co-occur on the wire,
 * so the mapping is exact in both directions for all six cadences.
 *
 * Every parameter is defaulted, so a bare `Recurrence(RecurrenceFrequency.Daily)` stays valid and the
 * tolerant posture holds: an additive/unknown wire `type` degrades to [RecurrenceFrequency.Unknown]
 * rather than crashing the reader (mirroring the `...Wire.Unknown` fallback the status enums use) while
 * [rawType] keeps the token itself, so an unrenderable cadence survives a cache/backup round-trip
 * instead of being destroyed by it (#382).
 */
data class Recurrence(
    val frequency: RecurrenceFrequency,
    /** Weekly only — `"Mon"`..`"Sun"` (the wire ships `chrono::Weekday`'s Display form). */
    val days: List<String> = emptyList(),
    /** The cycle multiplier: `every_n_days.n`, or `monthly`/`yearly`'s `interval`. `1` when absent. */
    val interval: Int? = null,
    /** Monthly only — which day *within* the cycle ("the 15th" vs "the last Friday"). */
    val monthlyAnchor: MonthlyAnchor? = null,
    /** Yearly only — the month, `1..12`. */
    val month: Int? = null,
    /** Yearly only — the day of that month, `1..31`. */
    val day: Int? = null,
    /** Custom only — the raw RFC-5545 RRULE, preserved verbatim so an imported rule is never lost. */
    val rrule: String? = null,
    /** The optional upper bound. An absent wire `end` **is** [RecurrenceBound.Never] — see there. */
    val bound: RecurrenceBound = RecurrenceBound.Never,
    /**
     * The raw wire `type` token, kept **only** when [frequency] is [RecurrenceFrequency.Unknown] — so a
     * cadence this client version cannot model still round-trips through the cache and the Backup file
     * under its own name instead of collapsing to the literal string `"Unknown"` (#382). Null for every
     * modelled cadence, which keeps `Recurrence(Daily)` equal to a `Daily` rule read off the wire.
     *
     * Bounded, deliberately: the *token* survives, its unmodelled parameters do not. All six cadences
     * the backend defines are modelled above, so this only fires on a future seventh — at which point
     * the fix is to model it, not to widen this.
     */
    val rawType: String? = null,
)

/**
 * How often a [Recurrence] fires — the six `Cadence` variants the backend defines
 * (`backend/src/models/recurrence.rs`), condensed from the wire `recurrence.type`. An unmodelled token
 * degrades to [Unknown] so a definition with an additive cadence keeps parsing.
 *
 * Adding entries is **cache-safe**: the cached column stores the enum NAME, and a row written by an
 * older build can only ever contain one of the older names.
 */
enum class RecurrenceFrequency {
    Daily,

    /** `every_n_days` — fires every `Recurrence.interval` days. */
    EveryNDays,
    Weekly,
    Monthly,
    Yearly,

    /** A raw RFC-5545 rule the backend could not express as one of the above; see `Recurrence.rrule`. */
    Custom,

    /**
     * Fallback for an additive/unknown wire `type` (kept distinct so the row stays usable). The token
     * itself is preserved in `Recurrence.rawType`.
     */
    Unknown,
}

/**
 * The optional upper bound on a [Recurrence] — the domain projection of the wire's nested `end` object.
 * [Never] and [OnDate]/[AfterCount] are mutually exclusive (RFC 5545 forbids `UNTIL` with `COUNT`).
 *
 * **An absent `end` key IS [Never]**, and is the only encoding of it the server ever emits: its
 * `Serialize` skips the key when the bound is never. The reader still tolerates an explicit
 * `{"type":"never"}` because the backend's `Deserialize` accepts one.
 */
sealed interface RecurrenceBound {

    /** Open-ended — "the trash goes out every Tuesday", forever. The default. */
    data object Never : RecurrenceBound

    /** Bounded by a date, **inclusive of that whole local day** (the wire's `UNTIL`). */
    data class OnDate(val date: LocalDate) : RecurrenceBound

    /** Bounded by a number of firings from the series anchor (the wire's `COUNT`). */
    data class AfterCount(val n: Int) : RecurrenceBound
}

/**
 * Which day a [RecurrenceFrequency.Monthly] rule lands on within its cycle — the domain projection of
 * the wire's nested `on` object (`MonthlyAnchor`).
 */
sealed interface MonthlyAnchor {

    /** "The 15th of every month" — [day] is `1..31`. */
    data class DayOfMonth(val day: Int) : MonthlyAnchor

    /**
     * "The second Wednesday of every month" — mirrors RFC 5545 `BYDAY=2WE`. [nth] is `1..5` **or `-1`
     * for "last"** (it is an `i8` on the wire, so a negative value is expected, not corrupt);
     * [weekday] is `"Mon"`..`"Sun"`.
     */
    data class NthWeekday(val nth: Int, val weekday: String) : MonthlyAnchor
}
