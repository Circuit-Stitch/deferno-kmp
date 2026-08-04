package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The element shape of `GET /habits/{id}/occurrences` — `Envelope_Vec_HabitOccurrence`, **not** the
 * unified `Occurrence` the old [OccurrenceDto] KDoc claimed for all three kinds (#390, ADR-0053).
 * Verified against the Rust, which is normative (ADR-0053 decision 5):
 * `backend/src/models/occurrence.rs` → `pub struct HabitOccurrence { habit_id, date, done_at }`.
 *
 * **Three fields, and that is the whole shape.** A habit firing has:
 * - **no id of its own** — the row is keyed `(habit_id, date)` server-side, which is why the retired
 *   `occurrenceEntity`'s server-UUID primary key could never be joined against anything the client
 *   writes (the write path keys on `(kind, definitionId, date)`, `OccurrenceTargets.of`);
 * - **no `status`** — there is no habit occurrence status enum anywhere in the backend. Done-ness is
 *   `done_at != null` and nothing else: no habit `skipped`, and no habit punctuality on the wire;
 * - **no `complete_by`** — the deadline lives on the definition, so the punctuality split for a habit
 *   is computed client-side from the definition's deadline for that date (see `toFact`).
 *
 * [doneAt] is `Option<DateTime<Utc>>` carrying **no** serde attribute — in particular no
 * `skip_serializing_if` — so unlike every optional field on [EventOccurrenceDto] the key is *always
 * emitted*, explicitly `null` for a firing the user has not ticked. It still carries a Kotlin default
 * because a JSON `null` under [com.circuitstitch.deferno.core.network.DefernoJson]'s
 * `coerceInputValues` needs a default to land on.
 *
 * [habitId] is decoded for faithfulness but is **not** the fact key: after a recurrence-rule change
 * the list is addressed by the chain Head while the rows carry their owning Segment's id (#574), so
 * the mapper takes the definition id from the caller instead of reading it off the row.
 */
@Serializable
data class HabitOccurrenceDto(
    @SerialName("habit_id") val habitId: String,
    val date: String,
    @SerialName("done_at") val doneAt: String? = null,
)
