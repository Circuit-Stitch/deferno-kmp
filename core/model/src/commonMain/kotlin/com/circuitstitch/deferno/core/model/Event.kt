package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * An Event as the rest of the app sees it — a **recurring definition** (CONTEXT.md → "Event"), the
 * clean domain projection of the wire `event` item (ADR-0011). Like [Habit]/[Chore] it is governed by
 * the definition "light switch" [DefinitionState] and carries a [recurrence]; an Event adds a fixed
 * time window — [completeBy] is the start, [endTime] the end — and an [allDay] flag.
 *
 * **Definition vs Occurrence (glossary).** This is the *definition*; one dated firing is a separate
 * [Occurrence] with its own [OccurrenceState].
 *
 * **Time-of-day (#348).** [completeBy]/[endTime] carry the start/end *days*; [startTimeOfDay]/
 * [endTimeOfDay] carry the clock time within them (`null` = all-day). [allDay] is now **derived,
 * read-only** server-side (true iff both times are `null`) and ignored on input — kept for one
 * deprecation cycle.
 */
data class Event(
    val id: EventId,
    val orgSlug: String,
    val title: String,
    val definitionState: DefinitionState,
    val recurrence: Recurrence? = null,
    val allDay: Boolean = false,
    val completeBy: Instant? = null,
    val endTime: Instant? = null,
    // The start/end clock times (#348), "HH:MM" on the wire (`start_time_of_day`/`end_time_of_day`);
    // `null` = all-day on that axis. The day comes from [completeBy]/[endTime].
    val startTimeOfDay: LocalTime? = null,
    val endTimeOfDay: LocalTime? = null,
    val labels: List<String> = emptyList(),
    val parentId: TaskId? = null,
    val pinned: Boolean = false,
    val sequence: Long? = null,
    val ref: String? = null,
    val dateCreated: Instant,
    val deletedAt: Instant? = null,
    val hydration: HydrationState = HydrationState.Summary,
    // Full-only enrichment — populated when [hydration] == [HydrationState.Full].
    val ownerOrgId: OrgId? = null,
    val description: String? = null,
    val seriesId: String? = null,
    // Server-derived dependency flags (ADR-0034, #289), read-only truth: [blocked] when an ancestor is
    // blocked (the flag inherits down the tree across kinds); [isBlocker] when this gates another. Both
    // default `false` so a payload omitting them decodes cleanly. Edges (`blockedBy`) are Task-only.
    val blocked: Boolean = false,
    val isBlocker: Boolean = false,
) {
    /** Whether this row is a soft-delete tombstone (`deleted_at` present). */
    val isDeleted: Boolean get() = deletedAt != null
}
