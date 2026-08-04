package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The faithful flat wire DTO for one **calendar feed** row — the element shape of
 * `GET /tasks/calendar` (`Envelope_Vec_CalendarEvent`, #74). The feed unifies a recurring firing, a
 * one-off dated item, and a synced external event into one dated block: [taskId] is the underlying
 * item **and the id every occurrence endpoint keys on**, [seriesId] the recurring series the firing
 * belongs to (absent for a one-off, and never a path key — #380), [kind] the work-item kind the row
 * projects, and [status] the item's `TaskStatus`.
 *
 * **[status] is the item's, not the firing's.** This feed row carries no occurrence status: the
 * backend projects the *definition's* `TaskStatus` onto every date it fires on, so the same value
 * repeats across a series and changes only when the definition does. An occurrence status does exist
 * on the wire — it is just on the kind-scoped occurrence endpoints (`GET /habits/{id}/occurrences` and
 * its Chore/Event siblings), which is where the client gets its facts (ADR-0053 decision 4). Reading
 * this field as "how that day went" is the defect #397 exists to close.
 *
 * [kind] has been a **required** `CalendarEvent` property since #311, added precisely so a client can
 * route a kind-scoped occurrence action straight from the feed row rather than maintaining a
 * `series_id -> kind` index. It is still declared with a default here, for the same reason every other
 * field is: an old cached/replayed payload must decode rather than throw (ADR-0005).
 *
 * Lossless + tolerant like the sibling read DTOs (ADR-0011/0005): snake_case via [SerialName], the
 * [status] and [kind] enums default to their `Unknown` member so an additive token degrades, [source]
 * is a free string the mapper condenses (`"deferno"` / `"google_calendar"`), and the unmodelled
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
    val kind: ItemKindWire = ItemKindWire.Unknown,
    val status: TaskStatusWire = TaskStatusWire.Unknown,
    val source: String = "deferno",
    val labels: List<String> = emptyList(),
    @SerialName("external_url") val externalUrl: String? = null,
    @SerialName("calendar_color") val calendarColor: String? = null,
    @SerialName("calendar_name") val calendarName: String? = null,
)
