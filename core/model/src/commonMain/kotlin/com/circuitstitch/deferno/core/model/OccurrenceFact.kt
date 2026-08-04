package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * What the server holds **on record** for one dated firing — a *fact*, never a reading (ADR-0053
 * decision 4, CONTEXT.md → "Occurrence state"). This is the half of an [OccurrenceState] that is
 * genuinely stored: the resolution and its timestamps. The Scheduled-vs-Missed split is *not* here
 * and never will be — it is a function of `today`, so it is derived at render time by
 * [resolveOccurrenceState] and is never a column.
 *
 * **Keyed by (kind, definitionId, date)** — the identity the write path has always used, built by
 * `OccurrenceTargets.of`. [definitionId] is the recurring **item** id (the chain Head the firing
 * projects from), *never* the series id: a habit occurrence has no id of its own on the wire at all,
 * which is why the old `occurrenceEntity`'s server-UUID primary key could not be joined against
 * anything the client writes.
 *
 * [completeBy] is the deadline this firing carried **at the time it was resolved**, kept because the
 * punctuality split is a function of it (see [completionResolution]) and the definition's live
 * `completeBy` is a moving [Recurrence] cursor that has since walked on.
 */
data class OccurrenceFact(
    val kind: ItemKind,
    val definitionId: String,
    val date: LocalDate,
    val resolution: OccurrenceResolution,
    val doneAt: Instant? = null,
    val completeBy: Instant? = null,
)

/**
 * The **stored** partition of the wire's occurrence vocabulary — the only values a server row can
 * actually hold. Deliberately a distinct type from [OccurrenceState], which is the wider *reading*
 * over these plus `Missed` and `Unknown`.
 *
 * Five members, not four, because the stored vocabulary genuinely differs per kind and this is the
 * union of it (all verified against the Rust, which is normative — ADR-0053 decision 5):
 * - **Chore** stores four — `ChoreOccurrenceStatus` is `in_progress`/`done_on_time`/`done_late`/
 *   `skipped` (the v0.2 wire spelling `dropped` is a serde alias of the same variant).
 * - **Event** stores five — `OccurrenceStatus` adds a genuinely **stored** `scheduled`, and its list
 *   endpoint returns only stored rows.
 * - **Habit** stores no status at all: done-ness is `done_at != null` and nothing else. There is no
 *   habit `Skipped` and no habit punctuality on the wire.
 *
 * [Scheduled] here therefore means "the server holds a row for this firing that records no progress"
 * — a fact. It is **not** the derived "nothing has happened yet" reading, which is
 * [OccurrenceState.Scheduled] and is produced by absence, not presence.
 */
enum class OccurrenceResolution {
    Scheduled,
    InProgress,
    DoneOnTime,
    DoneLate,
    Skipped,
    ;

    /** Whether this resolution is a final, written outcome (the Rust's `is_terminal`). */
    val isTerminal: Boolean
        get() = this == DoneOnTime || this == DoneLate || this == Skipped
}

/**
 * The punctuality split, mirroring the backend's `decide_chore_done_status`
 * (`backend/src/handlers/occurrences.rs:1164-1173`). **The bound is inclusive** — finishing exactly on
 * the deadline is on time (`done_at <= complete_by`).
 *
 * A firing with no recorded deadline cannot be late: with nothing to be late *against*, the only
 * honest reading is on time.
 */
fun completionResolution(doneAt: Instant, completeBy: Instant?): OccurrenceResolution =
    if (completeBy != null && doneAt > completeBy) OccurrenceResolution.DoneLate
    else OccurrenceResolution.DoneOnTime
