package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The element shape of `GET /events/{id}/occurrences` — `Envelope_Vec_Occurrence`, the *unified*
 * `Occurrence` struct (`backend/src/models/occurrence.rs`). Of the three kind-scoped occurrence lists
 * this is the only one that really does return `Occurrence`; the habit and chore endpoints return
 * their own narrower shapes ([HabitOccurrenceDto], [ChoreOccurrenceDto]) — which is exactly the claim
 * [OccurrenceDto]'s KDoc used to make for all three, and got wrong (#390). [OccurrenceDto] survives,
 * re-scoped to the one shape it really describes: `ItemView.today_occurrence`.
 *
 * **This endpoint returns only *stored* rows** (`backend/src/handlers/event_occurrences.rs:57-60`),
 * so every element is a fact — including one whose [status] reads `scheduled`. For an event that is a
 * genuinely written row that records no progress, not a derivation, which is why
 * `OccurrenceStatus.toResolution` is total and never returns `null` the way its chore sibling does.
 *
 * **`dropped`, never `skipped`.** The event vocabulary is `scheduled`/`in_progress`/`done_on_time`/
 * `done_late`/`dropped` ([OccurrenceStatusWire]); the chore vocabulary spells the same terminal
 * `skipped` ([DerivedChoreOccurrenceStatusWire], with `dropped` kept only as a serde *alias* for rows
 * the v0.1 backend wrote). The two spellings never appear on the same endpoint, and both condense to
 * the single domain `OccurrenceResolution.Skipped`.
 *
 * **[comment] is singular and is an array.** That is the server's field name, not a typo here: the
 * Rust declares `pub comment: Vec<Comment>`. Both it and [attachments] are `#[serde(default)]`
 * *without* `skip_serializing_if`, so they are always emitted — `[]` rather than absent — and are
 * modelled as raw [JsonObject]s. Nothing in this slice reads them; they are carried untyped so that
 * the shape stays lossless and visible to the contract-fixture harness (an unmodelled wire field is
 * invisible to it, which is how #381 and #382 each shipped) without coupling the occurrence read to
 * [CommentDto]/[AttachmentViewDto], whose required keys are shaped for the *task* thread.
 *
 * The nine `skip_serializing_if = "Option::is_none"` fields — [doneAt], the six per-occurrence
 * `*_override`s, and the two reschedule markers — are genuinely *absent* when unset, so each is
 * nullable with a default. [labelsOverride] is nullable rather than defaulted to an empty list on
 * purpose: `null` means "fall through to the parent event's labels", while `[]` would mean "this
 * firing has no labels" — the override machinery's whole point is that those differ.
 *
 * [id] and [parentId] are non-`Option` server-side and stay required, matching [OccurrenceDto].
 * [parentId] is the *Segment* that owns the row after a rule change, so — like
 * [ChoreOccurrenceDto.segmentId] — it is not the fact key; the mapper takes the definition id from
 * the caller (the Head it addressed) instead.
 */
@Serializable
data class EventOccurrenceDto(
    val id: String,
    @SerialName("parent_id") val parentId: String,
    @SerialName("scheduled_date") val scheduledDate: String,
    @SerialName("complete_by") val completeBy: String,
    val status: OccurrenceStatusWire = OccurrenceStatusWire.Unknown,
    @SerialName("done_at") val doneAt: String? = null,
    val comment: List<JsonObject> = emptyList(),
    val attachments: List<JsonObject> = emptyList(),
    @SerialName("title_override") val titleOverride: String? = null,
    @SerialName("description_override") val descriptionOverride: String? = null,
    @SerialName("labels_override") val labelsOverride: List<String>? = null,
    @SerialName("assignee_override") val assigneeOverride: String? = null,
    @SerialName("desire_override") val desireOverride: Double? = null,
    @SerialName("productive_override") val productiveOverride: Double? = null,
    @SerialName("rescheduled_to") val rescheduledTo: String? = null,
    @SerialName("rescheduled_from") val rescheduledFrom: String? = null,
)
