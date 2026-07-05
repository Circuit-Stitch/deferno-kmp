package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The **write** wire DTOs the create flow POSTs (ADR-0016, #71): `CreateTaskPayload` → `POST /tasks`,
 * and the recurring kinds → `POST /habits|/chores|/events`. Unlike the read DTOs these carry **no
 * `id`** (the server assigns it — there is no client idempotency key in v0.1, ADR-0016) and only the
 * fields the user supplies in the New form. Snake_case wire keys via [SerialName]; nullable/defaulted
 * fields are omitted by the serializer's `explicitNulls = false` (DefernoJson) so an absent field
 * isn't sent as `null`.
 *
 * The Chore "Shared with a Group" / rotation control is **deferred** (Groups are backend-blocked,
 * ADR-0015), so [CreateChorePayload] carries no group field — a Chore is creatable without one.
 */

/** `POST /tasks`. Only [title] is required; the rest is optional New-form input. */
@Serializable
data class CreateTaskPayload(
    val title: String,
    val description: String? = null,
    @SerialName("complete_by") val completeBy: String? = null,
    @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
    val labels: List<String>? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val productive: Double? = null,
    val desire: Double? = null,
)

/** `POST /habits`. A recurring definition: [title] + the [recurrence] cadence. */
@Serializable
data class CreateHabitPayload(
    val title: String,
    val recurrence: RecurrenceDto,
    val description: String? = null,
    @SerialName("complete_by") val completeBy: String? = null,
    @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
    val labels: List<String>? = null,
)

/** `POST /chores`. A recurring definition (group/rotation deferred — ADR-0015). */
@Serializable
data class CreateChorePayload(
    val title: String,
    val recurrence: RecurrenceDto,
    @SerialName("cadence_mode") val cadenceMode: String? = null,
    val description: String? = null,
    @SerialName("complete_by") val completeBy: String? = null,
    @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
    val labels: List<String>? = null,
)

/**
 * `POST /events`. A fixed-window definition: [completeBy] start day + optional [endTime] end day,
 * with [startTimeOfDay]/[endTimeOfDay] ("HH:MM") for the clock time on each axis (#348). `all_day`
 * is **not** sent — the server derives it (true iff both time-of-day fields are absent).
 */
@Serializable
data class CreateEventPayload(
    val title: String,
    @SerialName("complete_by") val completeBy: String,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("start_time_of_day") val startTimeOfDay: String? = null,
    @SerialName("end_time_of_day") val endTimeOfDay: String? = null,
    val recurrence: RecurrenceDto? = null,
    val description: String? = null,
    val labels: List<String>? = null,
)

/**
 * `POST /items/{id}/convert` (ADR-0016 post-creation counterpart): change an existing item's kind.
 * Carries the target [type] (`task`/`habit`/`chore`/`event`) plus the fields the new kind needs that
 * the old kind lacked (a recurrence when converting *to* a recurring kind, an `end_time` for an event).
 */
@Serializable
data class ConvertItemPayload(
    val type: String,
    @SerialName("complete_by") val completeBy: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    val recurrence: RecurrenceDto? = null,
)

/**
 * `PATCH /tasks/{id}` body replacing the ordered dependency-edge list (#291): always-present
 * `blocked_by` (an empty array clears every edge — never absent, ADR-0011). Each entry is the
 * blocker's raw item UUID, optionally narrowed to one [BlockedByRefDto.occurrence] of a recurring
 * blocker (a deferred follow-up — the tree picker sends item-only edges).
 */
@Serializable
data class SetBlockedByPayload(
    @SerialName("blocked_by") val blockedBy: List<BlockedByRefDto>,
)
