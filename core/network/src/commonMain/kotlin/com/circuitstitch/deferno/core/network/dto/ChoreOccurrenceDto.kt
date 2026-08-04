package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The element shape of `GET /chores/{id}/occurrences` — `Envelope_Vec_ChoreOccurrenceView`, a
 * *derived view* and not a stored row (#390, ADR-0053). Verified against the Rust, which is normative
 * (ADR-0053 decision 5): `backend/src/handlers/occurrences.rs` → `pub struct ChoreOccurrenceView
 * { scheduled_date, status, complete_by, completed_at, segment_id }`.
 *
 * **Five fields — no `id`, no `parent_id`, no `chore_id`.** The stored `ChoreOccurrence` struct has a
 * `chore_id`, but this endpoint does not return it: it returns one view row per date in the requested
 * window, materialised from the chain's Segments plus a per-request derivation.
 *
 * **[status] is the read *superset*, [DerivedChoreOccurrenceStatusWire], not the settable four.** Two
 * of its members — `scheduled` and `missed` — are the server's *opinion about today*, computed against
 * `Utc::now().date_naive()` (`occurrences.rs:465`), while the `complete_by` in the same match arm
 * honours the user's zone. That inconsistency is a backend defect ADR-0053 decision 5 says is fixed in
 * Rust first, so the client neither copies it nor forks a second specification: it simply refuses to
 * store the derived arms at all. `toResolutionOrNull` returns `null` for those two and the caller
 * writes **no fact row**, leaving the Scheduled-vs-Missed reading to be derived against the user's
 * local today at render time. The client's own derivation has a third arm the wire also emits as
 * `skipped` — a past unstored date on a non-Active chore (`occurrences.rs:488-495`).
 *
 * **[completedAt], not `done_at`.** The chore view spells the completion timestamp differently from
 * both the habit row (`done_at`) and the unified event `Occurrence` (`done_at`); all three mean the
 * same thing and land on the same `OccurrenceFact.doneAt`. Like the habit row's it carries no
 * `skip_serializing_if`, so the key is always emitted and explicitly `null` when unresolved.
 *
 * **[segmentId] is a write address, not the fact key.** After a recurrence-rule change a chore is a
 * Series chain addressed by its Head, but Occurrence rows are keyed by *entity id* + date, so a
 * per-occurrence write for this date must be sent to the Segment that actually schedules it (#574).
 * The fact key stays `(kind, definitionId, date)` on the Head the caller asked for — see
 * `OccurrenceTargets` — so this field is decoded and carried by the DTO, not by the fact.
 *
 * [completeBy] and [segmentId] are non-`Option` in the Rust and are therefore required here, matching
 * the house rule that identity-critical fields have no default (cf. [CommentDto]).
 */
@Serializable
data class ChoreOccurrenceDto(
    @SerialName("scheduled_date") val scheduledDate: String,
    val status: DerivedChoreOccurrenceStatusWire = DerivedChoreOccurrenceStatusWire.Unknown,
    @SerialName("complete_by") val completeBy: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("segment_id") val segmentId: String,
)
