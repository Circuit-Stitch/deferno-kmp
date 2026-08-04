@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalTime
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * An Event as the rest of the app sees it — a **recurring definition** (CONTEXT.md → "Event"), the
 * clean domain projection of the wire `event` item (ADR-0011). Like [Habit]/[Chore] it is governed by
 * the definition "light switch" [DefinitionState] and carries a [recurrence]; an Event adds a fixed
 * time window — [completeBy] is the start, [endTime] the end — and an [allDay] flag.
 *
 * **Definition vs Occurrence (glossary).** This is the *definition*; one dated firing is an
 * [OccurrenceFact] keyed by `(kind, definitionId, date)` — where `definitionId` is *this* [id] — and
 * how that firing went is the separate render-time [OccurrenceState] derived from the fact, the
 * definition's own [definitionState], coverage and today (ADR-0053 decision 4). There is deliberately
 * no `Occurrence` type: a firing is a key plus a fact, never a row with an identity of its own. The
 * Event endpoint is the one kind that does ship a per-firing UUID, and it is still not the key — a
 * client cannot join on an id its Habit sibling has never had on the wire.
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
    // The soft target date + urgency bucket (#375) — see [Task.targetDate] for the full split. Distinct
    // from this Event's own WHEN window ([completeBy]/[endTime]): the target is when the person *wants*
    // it dealt with and drives sorting/surfacing only, so it never moves the Event on the calendar.
    // Wire `target_date` / `priority`.
    val targetDate: Instant? = null,
    val priority: Priority = Priority.Default,
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
    // Named explicitly for the Apple export: `description` collides with `-[NSObject description]`
    // and would otherwise land in Swift as the unstable `description_` (see [Task.description]).
    @property:ObjCName("itemDescription") val description: String? = null,
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
