@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalTime
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * A Habit as the rest of the app sees it — a **recurring definition** (CONTEXT.md → "Habit"), the
 * clean domain projection of the wire `habit` item (ADR-0011). Mirrors [Task]'s shape (identity vs
 * [ref], hydration, tombstone), but its lifecycle is the definition "light switch"
 * [DefinitionState] — **not** the Task [WorkingState] — and it carries a [recurrence] rule.
 *
 * **Definition vs Occurrence (glossary).** This is the *definition*: the recurring template and its
 * on/off [definitionState]. A single dated firing of it is a separate thing, and since #390 there is
 * no `Occurrence` *type* to point at: a firing is identified by `(kind, definitionId, date)`, what is
 * on record about it is an [OccurrenceFact] under that key, and how it went is the render-time
 * [OccurrenceState] read off that fact plus coverage and today. The definition's state and the
 * firing's state stay deliberately distinct types so neither is mistaken for the other (ADR-0053
 * decision 4).
 *
 * A Habit firing is the sharpest case for that split: the wire row is `{habit_id, date, done_at}`
 * with no id and no status column, so a Habit occurrence is *only* addressable by this definition's
 * id and a date.
 */
data class Habit(
    val id: HabitId,
    val orgSlug: String,
    val title: String,
    val definitionState: DefinitionState,
    val recurrence: Recurrence? = null,
    val labels: List<String> = emptyList(),
    val parentId: TaskId? = null,
    val completeBy: Instant? = null,
    // The deadline's clock time (#348); `null` = no time-of-day. Wire `deadline_time_of_day`.
    val deadlineTimeOfDay: LocalTime? = null,
    // The soft target date + urgency bucket (#375), series-level like [completeBy] — see [Task.targetDate]
    // for the full split. Sorting/surfacing only; independent of the hard deadline. Wire
    // `target_date` / `priority`.
    val targetDate: Instant? = null,
    val priority: Priority = Priority.Default,
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
    // The offline expansion inputs behind [seriesId] (#410, ADR-0053) — the frozen anchor, its zone,
    // the Segment bound and the exceptions that [expandOccurrenceGrid] needs to reproduce this
    // definition's [[Occurrence grid]] with the network gone. `null` is the wire's deliberate
    // ELISION — "this device cannot reproduce that grid" — and never an empty one; see [SeriesInputs].
    val series: SeriesInputs? = null,
    // Server-derived dependency flags (ADR-0034, #289), read-only truth: [blocked] when an ancestor is
    // blocked (the flag inherits down the tree across kinds); [isBlocker] when this gates another. Both
    // default `false` so a payload omitting them decodes cleanly. Edges (`blockedBy`) are Task-only.
    val blocked: Boolean = false,
    val isBlocker: Boolean = false,
) {
    /** Whether this row is a soft-delete tombstone (`deleted_at` present). */
    val isDeleted: Boolean get() = deletedAt != null
}
