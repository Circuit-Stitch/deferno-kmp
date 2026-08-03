package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate

/**
 * The recurrence rule a recurring definition (a [Habit] / [Chore] / [Event]) fires on (CONTEXT.md →
 * "Definition" / "Recurrence"). The clean domain projection of the wire `recurrence` object (ADR-0011
 * condense-at-edge), and exactly two things: a [cadence] — *which* days it fires on, carrying whatever
 * parameters that cadence needs — and an optional upper [bound] on how long it keeps going.
 *
 * The two are orthogonal: any cadence can carry any bound. That is why the bound is a peer of the
 * cadence rather than a field repeated inside all seven variants (and why the wire nests only `end`).
 */
data class Recurrence(
    val cadence: Cadence,
    /** The optional upper bound. An absent wire `end` **is** [RecurrenceBound.Never] — see there. */
    val bound: RecurrenceBound = RecurrenceBound.Never,
)

/**
 * How a [Recurrence] fires — the six `Cadence` variants the backend defines
 * (`backend/src/models/recurrence.rs`), plus [Unmodelled] for a seventh a future backend adds.
 *
 * The wire is FLAT: the backend hand-writes `Serialize`/`Deserialize`, so each cadence's own fields are
 * hoisted to the top level of the `recurrence` object and only `end` stays nested.
 *
 * | variant | wire |
 * |---|---|
 * | [Daily] | `{"type":"daily"}` |
 * | [EveryNDays] | `{"type":"every_n_days","n":3}` |
 * | [Weekly] | `{"type":"weekly","days":["Mon","Wed"]}` |
 * | [Monthly] | `{"type":"monthly","interval":2,"on":{…}}` |
 * | [Yearly] | `{"type":"yearly","interval":1,"month":6,"day":14}` |
 * | [Custom] | `{"type":"custom","rrule":"FREQ=…"}` |
 *
 * **Sealed here while the DTO stays a flat bag — and the asymmetry is deliberate.**
 * `RecurrenceDto` must remain flat and all-defaulted because kotlinx *throws* on an unknown polymorphic
 * discriminator, and a throw inside the `/items` decode is precisely the #381 cold-sync stall. But this
 * type is never deserialized — it is built by hand in exactly two places (the network mapper and the
 * row codec) — so it can afford to make illegal states unrepresentable, and should. Its flat
 * predecessor needed a six-row table in its own KDoc just to say which of eight nullable fields applied
 * to which frequency; that table *was* the type it declined to write. The variants say it now.
 *
 * That also un-condenses the wire's two numeric keys. `every_n_days`'s `n` and `monthly`/`yearly`'s
 * `interval` used to share one nullable `interval` field, because a flat bag carrying both would have
 * been a second illegal state. Each variant names its own, so no reader has to know which key it meant.
 */
sealed interface Cadence {

    /** Every day. */
    data object Daily : Cadence

    /** Every [n] days — the wire's `every_n_days.n`. */
    data class EveryNDays(val n: Int) : Cadence

    /** The given weekdays, `"Mon"`..`"Sun"` (the wire ships `chrono::Weekday`'s Display form). */
    data class Weekly(val days: List<String>) : Cadence

    /**
     * Every [interval] months, optionally [on] a particular day within the cycle ("the 15th" vs "the
     * last Friday"). [on] is nullable because a rule whose anchor could not be read is still a usable
     * monthly rule — it just cannot say which day.
     */
    data class Monthly(val interval: Int, val on: MonthlyAnchor? = null) : Cadence

    /** Every [interval] years, on [month] (`1..12`) / [day] (`1..31`). */
    data class Yearly(val interval: Int, val month: Int, val day: Int) : Cadence

    /** A raw RFC-5545 rule the backend could not express as one of the above; [rrule] verbatim. */
    data class Custom(val rrule: String) : Cadence

    /**
     * A cadence this build cannot model; the wire token survives verbatim (#382) so an unrenderable
     * rule round-trips through the cache and the Backup file under its own name instead of collapsing
     * to a literal `"Unknown"` and being destroyed by the trip.
     *
     * Bounded, deliberately: the *token* survives, its unmodelled parameters do not — there is nowhere
     * to put them, and inventing a bag for them would re-import the shape this type just deleted. All
     * six cadences the backend defines are modelled above, so this only fires on a future seventh, at
     * which point the fix is to model it rather than to widen this.
     */
    data class Unmodelled(val rawType: String) : Cadence
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
 * Which day a [Cadence.Monthly] rule lands on within its cycle — the domain projection of the wire's
 * nested `on` object (`MonthlyAnchor`).
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
