package com.circuitstitch.deferno.core.model

import kotlin.time.Instant

/**
 * An Event as the rest of the app sees it — a **recurring definition** (CONTEXT.md → "Event"), the
 * clean domain projection of the wire `event` item (ADR-0011). Like [Habit]/[Chore] it is governed by
 * the definition "light switch" [DefinitionState] and carries a [recurrence]; an Event adds a fixed
 * time window — [completeBy] is the start, [endTime] the end — and an [allDay] flag.
 *
 * **Definition vs Occurrence (glossary).** This is the *definition*; one dated firing is a separate
 * [Occurrence] with its own [OccurrenceState].
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
) {
    /** Whether this row is a soft-delete tombstone (`deleted_at` present). */
    val isDeleted: Boolean get() = deletedAt != null
}
