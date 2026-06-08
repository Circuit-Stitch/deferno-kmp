package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The faithful flat wire DTOs for the full single-item Habit/Chore/Event payloads (ADR-0011,
 * CONTRACT-NOTES → "Items"). They mirror [TaskDetailDto] for the recurring kinds: the create
 * endpoints (`POST /habits|/chores|/events`) and the detail reads (`GET /habits/{id}`, …) return the
 * **same full single-item shape** the `/items` union ships per kind (the `habit`/`chore`/`event`
 * variant of [ItemView]) — but as a top-level object, not a discriminated list element. Each combines
 * the envelope fields (`ref`/`org_slug`/`sequence`) with the flattened recurring payload.
 *
 * Lossless + tolerant exactly as [TaskDetailDto]: snake_case via [SerialName], every enum field
 * defaults to `...Wire.Unknown` so additive tokens degrade rather than crash, and the tolerant reader
 * silently ignores the unmodelled fields (`actions`, `mood_*`, `attachments`, `group_id`, `kind`).
 * The DTO→domain mapping lives in `mapper/RecurringItemMapper.kt`.
 */

/** The full `habit` payload (`POST /habits`, `GET /habits/{id}`) — same fields as [ItemView.Habit]. */
@Serializable
data class HabitDetailDto(
    val id: String,
    @SerialName("org_slug") val orgSlug: String,
    @SerialName("owner_org_id") val ownerOrgId: String? = null,
    val ref: String? = null,
    val sequence: Long? = null,
    val title: String,
    val status: DefStatusWire = DefStatusWire.Unknown,
    val labels: List<String> = emptyList(),
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("complete_by") val completeBy: String? = null,
    val pinned: Boolean = false,
    @SerialName("date_created") val dateCreated: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val description: String? = null,
    val recurrence: RecurrenceDto? = null,
    @SerialName("series_id") val seriesId: String? = null,
)

/** The full `chore` payload (`POST /chores`, `GET /chores/{id}`) — same fields as [ItemView.Chore]. */
@Serializable
data class ChoreDetailDto(
    val id: String,
    @SerialName("org_slug") val orgSlug: String,
    @SerialName("owner_org_id") val ownerOrgId: String? = null,
    val ref: String? = null,
    val sequence: Long? = null,
    val title: String,
    val status: DefStatusWire = DefStatusWire.Unknown,
    val labels: List<String> = emptyList(),
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("complete_by") val completeBy: String? = null,
    val pinned: Boolean = false,
    @SerialName("date_created") val dateCreated: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val description: String? = null,
    val recurrence: RecurrenceDto? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("cadence_mode") val cadenceMode: String? = null,
)

/** The full `event` payload (`POST /events`, `GET /events/{id}`) — same fields as [ItemView.Event]. */
@Serializable
data class EventDetailDto(
    val id: String,
    @SerialName("org_slug") val orgSlug: String,
    @SerialName("owner_org_id") val ownerOrgId: String? = null,
    val ref: String? = null,
    val sequence: Long? = null,
    val title: String,
    val status: DefStatusWire = DefStatusWire.Unknown,
    val labels: List<String> = emptyList(),
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("complete_by") val completeBy: String? = null,
    val pinned: Boolean = false,
    @SerialName("date_created") val dateCreated: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val description: String? = null,
    val recurrence: RecurrenceDto? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("end_time") val endTime: String? = null,
)
