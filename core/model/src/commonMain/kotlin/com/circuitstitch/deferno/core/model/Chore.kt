package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * A Chore as the rest of the app sees it — a **recurring definition** (CONTEXT.md → "Chore"), the
 * clean domain projection of the wire `chore` item (ADR-0011). Like [Habit] it is governed by the
 * definition "light switch" [DefinitionState] and carries a [recurrence]; it adds the chore-specific
 * [cadenceMode] (e.g. `rolling` vs `fixed`) the wire ships.
 *
 * **Deferred (ADR-0015):** the "Shared with a Group" / rotation control — Groups are backend-blocked,
 * so a Chore is creatable in v1 *without* a group, and this model carries no group/rotation field.
 *
 * **Definition vs Occurrence (glossary).** This is the *definition*; one dated firing is a separate
 * [Occurrence] with its own [OccurrenceState].
 */
data class Chore(
    val id: ChoreId,
    val orgSlug: String,
    val title: String,
    val definitionState: DefinitionState,
    val recurrence: Recurrence? = null,
    val cadenceMode: String? = null,
    val labels: List<String> = emptyList(),
    val parentId: TaskId? = null,
    val completeBy: Instant? = null,
    // The deadline's clock time (#348); `null` = no time-of-day. Wire `deadline_time_of_day`.
    val deadlineTimeOfDay: LocalTime? = null,
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
