package com.circuitstitch.deferno.core.model

/**
 * The clean domain replacement for the API's overloaded "status" (CONTEXT.md → "Item state",
 * ADR-0011). The wire ships **six** enums with inconsistent casing across three unrelated axes;
 * the network DTO→domain mapper (#18) condenses them into the three distinctly-named domain enums
 * below. Domain constants are idiomatic PascalCase — the exact wire tokens live only on the DTO
 * `@SerialName`s in `core:network`, never here.
 */

/**
 * A [Task]'s progress through its own lifecycle (CONTEXT.md → "Working state"). Condensed from the
 * wire `TaskStatus` (`open`, `in-progress`, `in-review`, `done`, `dropped`).
 */
enum class WorkingState {
    Open,
    InProgress,
    InReview,
    Done,
    Dropped,
    ;

    /** Whether the Task has reached an end of its lifecycle — used to filter active work. */
    val isTerminal: Boolean get() = this == Done || this == Dropped
}

/**
 * The "light switch" on a recurring definition — a Habit/Chore/Event (CONTEXT.md → "Definition
 * state"). Condensed from the wire `DefStatus` (`active`, `in-review`, `archived`). `InReview` is
 * retained faithfully pending a backend clarification (ADR-0011).
 */
enum class DefinitionState {
    Active,
    InReview,
    Archived,
}

/**
 * How one dated firing of a recurring definition went (CONTEXT.md → "Occurrence state"). The
 * client only ever *writes* a coarse [OccurrenceAction]; these finer read states — `Scheduled`,
 * `Missed`, and the on-time/late punctuality split of "done" — are **server-derived**. Condensed
 * from the wire `OccurrenceStatus`/`DerivedChoreOccurrenceStatus` family; `Missed` is kept
 * distinct from `Skipped`.
 */
enum class OccurrenceState {
    Scheduled,
    InProgress,
    DoneOnTime,
    DoneLate,
    Skipped,
    Missed,
    ;

    /** Whether this firing is finished (any "done" punctuality, skipped, or missed). */
    val isResolved: Boolean
        get() = this == DoneOnTime || this == DoneLate || this == Skipped || this == Missed
}

/**
 * The coarse action the client *writes* against an Occurrence (ADR-0011). The mapper emits the
 * kind-appropriate wire token — a chore completes/`skipped`, an event completes/`dropped` — so the
 * domain never has to know the wire's read/write asymmetry. `Scheduled`/`Missed`/punctuality are
 * read-only and never written.
 */
enum class OccurrenceAction {
    Start,
    Complete,
    Skip,
}
