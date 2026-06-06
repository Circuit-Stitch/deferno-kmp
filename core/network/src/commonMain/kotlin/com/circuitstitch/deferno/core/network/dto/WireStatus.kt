package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The six wire "status" enums (ADR-0011, CONTRACT-NOTES → "Status"). The Deferno API overloads
 * "status" across six enums on three unrelated axes, with inconsistent casing — `TaskStatus`
 * hyphenates (`in-progress`), the occurrence family underscores (`in_progress`). Each is modelled
 * faithfully here: one `@Serializable` enum per wire enum, every constant carrying its EXACT wire
 * token via [SerialName], plus an [Unknown] fallback. The clean domain enums (WorkingState /
 * DefinitionState / OccurrenceState) live in `core:model`; the wire→domain condensation lives in
 * `mapper/` — this layer stays lossless.
 *
 * **Unknown-fallback mechanism (ADR-0005 tolerant reader).** [DefernoJson] sets
 * `coerceInputValues = true`, which coerces an *unrecognised* enum token to the **property
 * default**. So every DTO field of one of these enum types defaults to `...Wire.Unknown` — an
 * additive backend status decodes to [Unknown] rather than crashing the reader, and the mapper then
 * degrades it to a safe domain default (e.g. a Task stays visible as `Open`).
 */

/** Wire `TaskStatus` → domain `WorkingState`. Note the hyphenated `in-progress`/`in-review`. */
@Serializable
enum class TaskStatusWire {
    @SerialName("open")
    Open,

    @SerialName("in-progress")
    InProgress,

    @SerialName("in-review")
    InReview,

    @SerialName("done")
    Done,

    @SerialName("dropped")
    Dropped,

    /** Fallback for an additive/unknown wire token (coerced here by [DefernoJson]). */
    Unknown,
}

/** Wire `DefStatus` → domain `DefinitionState` (the Habit/Chore/Event "light switch"). */
@Serializable
enum class DefStatusWire {
    @SerialName("active")
    Active,

    @SerialName("in-review")
    InReview,

    @SerialName("archived")
    Archived,

    /** Fallback for an additive/unknown wire token (coerced here by [DefernoJson]). */
    Unknown,
}

/**
 * Wire `OccurrenceStatus` → domain `OccurrenceState`. Underscored tokens; carries the server-derived
 * punctuality split (`done_on_time`/`done_late`). `dropped` (event terminal) maps to `Skipped`.
 */
@Serializable
enum class OccurrenceStatusWire {
    @SerialName("scheduled")
    Scheduled,

    @SerialName("in_progress")
    InProgress,

    @SerialName("done_on_time")
    DoneOnTime,

    @SerialName("done_late")
    DoneLate,

    @SerialName("dropped")
    Dropped,

    /** Fallback for an additive/unknown wire token (coerced here by [DefernoJson]). */
    Unknown,
}

/**
 * Wire `ChoreOccurrenceStatus` — the *settable* subset for chore occurrences (CONTRACT-NOTES →
 * read/write asymmetry). Modelled for faithfulness; the client writes via [OccurrenceAction] tokens.
 */
@Serializable
enum class ChoreOccurrenceStatusWire {
    @SerialName("in_progress")
    InProgress,

    @SerialName("done_on_time")
    DoneOnTime,

    @SerialName("done_late")
    DoneLate,

    @SerialName("skipped")
    Skipped,

    /** Fallback for an additive/unknown wire token (coerced here by [DefernoJson]). */
    Unknown,
}

/**
 * Wire `DerivedChoreOccurrenceStatus` — the *read* superset for chore occurrences: the settable
 * subset plus the server-derived `scheduled`/`missed`. Condensed to `OccurrenceState` in the mapper.
 */
@Serializable
enum class DerivedChoreOccurrenceStatusWire {
    @SerialName("scheduled")
    Scheduled,

    @SerialName("missed")
    Missed,

    @SerialName("in_progress")
    InProgress,

    @SerialName("done_on_time")
    DoneOnTime,

    @SerialName("done_late")
    DoneLate,

    @SerialName("skipped")
    Skipped,

    /** Fallback for an additive/unknown wire token (coerced here by [DefernoJson]). */
    Unknown,
}
