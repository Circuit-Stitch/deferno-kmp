package com.circuitstitch.deferno.core.model

import kotlin.time.Instant

/**
 * A Habit as the rest of the app sees it — a **recurring definition** (CONTEXT.md → "Habit"), the
 * clean domain projection of the wire `habit` item (ADR-0011). Mirrors [Task]'s shape (identity vs
 * [ref], hydration, tombstone), but its lifecycle is the definition "light switch"
 * [DefinitionState] — **not** the Task [WorkingState] — and it carries a [recurrence] rule.
 *
 * **Definition vs Occurrence (glossary).** This is the *definition*: the recurring template and its
 * on/off [definitionState]. A single dated firing of it is a separate [Occurrence] with its own
 * [OccurrenceState]; the two states are deliberately distinct types so neither is mistaken for the
 * other.
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
