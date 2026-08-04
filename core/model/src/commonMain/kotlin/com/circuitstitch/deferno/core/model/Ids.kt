package com.circuitstitch.deferno.core.model

import kotlin.jvm.JvmInline

/**
 * Stable identifier of a [Task] — the backend's UUID `id` (ADR-0001). It is the reconcile key:
 * a full-snapshot refresh upserts/removes rows by [TaskId], so it must be a faithful, non-blank
 * copy of the wire `id`. Distinct from the human-facing [Task.ref] (`{org_slug}-{sequence}`),
 * which can be absent on a freshly created row and is *not* an identity.
 */
@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "TaskId must not be blank" }
    }
}

/**
 * An Org's UUID (`owner_org_id`, ADR-0002) — the within-Account ownership boundary every Item
 * carries. Present on full (hydrated) items; absent on list summaries. Modelled distinctly from
 * the [Task.orgSlug] (the short `u-e4h2qk` slug used in `ref`).
 */
@JvmInline
value class OrgId(val value: String) {
    init {
        require(value.isNotBlank()) { "OrgId must not be blank" }
    }
}

/**
 * The backend User's UUID (`GET /auth/me` → `id`, CONTEXT.md → "User"). One [Account] authenticates
 * as exactly one backend [User], so this is the server-side identity, **not** the client-side
 * [AccountId] partition key (the two are distinct — an Account is the hard isolation boundary, a User
 * is who it signs in as). Carried as a faithful, non-blank copy of the wire `id`.
 */
@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "UserId must not be blank" }
    }
}

/**
 * Stable identifier of a [Habit] — the backend's UUID `id`. The reconcile key for the Habit cache,
 * exactly as [TaskId] is for Tasks: a faithful, non-blank copy of the wire `id`, distinct from the
 * human-facing [Habit.ref].
 */
@JvmInline
value class HabitId(val value: String) {
    init {
        require(value.isNotBlank()) { "HabitId must not be blank" }
    }
}

/** Stable identifier of a [Chore] — the backend's UUID `id` (the Chore reconcile key, like [TaskId]). */
@JvmInline
value class ChoreId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChoreId must not be blank" }
    }
}

/** Stable identifier of an [Event] — the backend's UUID `id` (the Event reconcile key, like [TaskId]). */
@JvmInline
value class EventId(val value: String) {
    init {
        require(value.isNotBlank()) { "EventId must not be blank" }
    }
}

// There is deliberately **no `OccurrenceId`** here (#390, ADR-0053 decision 4). A dated firing is
// identified by `(kind, definitionId, date)` — the identity the write path has always used through
// `OccurrenceTargets.of` — and what is stored about it is an [OccurrenceFact] under that key. The
// server's per-firing UUID was modelled here until #390 and had no production caller by then: a Habit
// occurrence has no id of its own on the wire at all (`HabitOccurrenceDto` is `{habit_id, date,
// done_at}`), so a UUID key could never be joined against anything this client writes, and keying a
// row on it is exactly what ADR-0053 replaced. `OccurrenceDto.id` stays a plain wire `String` on the
// one path that still decodes it (`today_occurrence` on a plan row), which is all it ever was.

/**
 * Which of the four item kinds an item is (CONTEXT.md → "Item"). The clean domain mirror of the wire
 * `type` discriminator (`task`/`habit`/`chore`/`event`). It is the explicit kind a New picker selects
 * (ADR-0015 — no field-inference) and the target a `convert` Command names (ADR-0016 counterpart).
 */
enum class ItemKind {
    Task,
    Habit,
    Chore,
    Event,
}
