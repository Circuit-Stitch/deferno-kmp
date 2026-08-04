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
 * How one dated firing of a recurring definition went (CONTEXT.md → "Occurrence state") — a
 * **reading, never a stored value** (ADR-0053 decision 4). It is derived at render time by
 * [resolveOccurrenceState] from the stored [OccurrenceFact], [OccurrenceCoverage], the parent's
 * [DefinitionState] and today; it is never persisted, and `occurrence_state` is never a column.
 *
 * **Not "server-derived".** That earlier claim was wrong in the way that mattered: only the
 * *resolution* and the punctuality inputs come from the server. The `Scheduled` vs `Missed` split is
 * a function of `today` over a firing with no resolution, which is exactly why the client can — and
 * must — re-derive it with the server gone. The backend says as much itself, annotating its
 * `DerivedChoreOccurrenceStatus` members "Scheduled — future or today, no record" and "Missed — past,
 * no record, chore Active".
 *
 * The stored half is the separate, narrower [OccurrenceResolution]; `Missed` and [Unknown] are the two
 * members that appear here and can never appear there.
 */
enum class OccurrenceState {
    Scheduled,
    InProgress,
    DoneOnTime,
    DoneLate,
    Skipped,
    Missed,

    /**
     * This device has never synced that date, so how the firing went is genuinely not known — and the
     * surface says so rather than letting an unsynced past day read as [Missed] (ADR-0053 decision 4).
     * Appended last: never reorder an existing member.
     */
    Unknown,
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
