package com.circuitstitch.deferno.core.model

/**
 * The recurrence rule a recurring definition (a [Habit] / [Chore] / [Event]) fires on (CONTEXT.md →
 * "Definition" / "Recurrence"). The clean domain projection of the wire `recurrence` object (ADR-0011
 * condense-at-edge): a [frequency] plus, for a [RecurrenceFrequency.Weekly] rule, the [days] it
 * recurs on. Modelled tolerantly — an additive/unknown wire `type` degrades to
 * [RecurrenceFrequency.Unknown] rather than crashing the reader, mirroring the `...Wire.Unknown`
 * fallback the status enums use.
 */
data class Recurrence(
    val frequency: RecurrenceFrequency,
    val days: List<String> = emptyList(),
)

/**
 * How often a [Recurrence] fires. Condensed from the wire `recurrence.type` (`daily`/`weekly`/…);
 * an unmodelled token degrades to [Unknown] so a definition with an additive cadence keeps parsing.
 */
enum class RecurrenceFrequency {
    Daily,
    Weekly,
    Monthly,
    Yearly,

    /** Fallback for an additive/unknown wire `type` (kept distinct so the row stays usable). */
    Unknown,
}
