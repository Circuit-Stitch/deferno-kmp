package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The faithful flat wire DTO for one **calendar feed** row — the element shape of
 * `GET /tasks/calendar` (`Envelope_Vec_CalendarEvent`, #74). The feed unifies a recurring firing, a
 * one-off dated item, and a synced external event into one dated block: [taskId] is the underlying
 * item, [seriesId] the recurring series the occurrence endpoints key on (absent for a one-off), and
 * [status] is the item's `TaskStatus` (the feed reports progress as the Task axis even for firings —
 * there is **no `kind` and no occurrence-status** on the wire).
 *
 * Lossless + tolerant like the sibling read DTOs (ADR-0011/0005): snake_case via [SerialName], the
 * [status] enum defaults to [TaskStatusWire.Unknown] so an additive token degrades, [source] is a free
 * string the mapper condenses (`"deferno"` / `"google_calendar"`), and the unmodelled
 * overlay/attachment fields are ignored by the tolerant reader.
 */
@Serializable
data class CalendarEventDto(
    val id: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("series_id") val seriesId: String? = null,
    val title: String,
    val start: String,
    val end: String,
    @SerialName("all_day") val allDay: Boolean = false,
    val status: TaskStatusWire = TaskStatusWire.Unknown,
    val source: String = "deferno",
    val labels: List<String> = emptyList(),
    @SerialName("external_url") val externalUrl: String? = null,
    @SerialName("calendar_color") val calendarColor: String? = null,
    @SerialName("calendar_name") val calendarName: String? = null,
)
