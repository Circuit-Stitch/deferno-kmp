@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalTime
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * A Chore as the rest of the app sees it — a **recurring definition** (CONTEXT.md → "Chore"), the
 * clean domain projection of the wire `chore` item (ADR-0011). Like [Habit] it is governed by the
 * definition "light switch" [DefinitionState] and carries a [recurrence]; it adds the chore-specific
 * [cadenceMode] the wire ships.
 *
 * **[cadenceMode] is not a [Cadence]** — the near-collision is the wire's vocabulary, not ours. The
 * [recurrence]'s [Cadence] says *which days this chore fires on*; [CadenceMode] says how the schedule
 * *advances once a firing is closed out*, and the two never substitute for one another.
 *
 * **Deferred (ADR-0015):** the "Shared with a Group" / rotation control — Groups are backend-blocked,
 * so a Chore is creatable in v1 *without* a group, and this model carries no group/rotation field.
 *
 * **Definition vs Occurrence (glossary).** This is the *definition*; one dated firing is an
 * [OccurrenceFact] keyed by `(kind, definitionId, date)` — where `definitionId` is *this* [id] — and
 * how that firing went is the separate render-time [OccurrenceState] derived from the fact, the
 * definition's own [definitionState], coverage and today (ADR-0053 decision 4). There is deliberately
 * no `Occurrence` type: a firing is a key plus a fact, never a row with an identity of its own.
 */
data class Chore(
    val id: ChoreId,
    val orgSlug: String,
    val title: String,
    val definitionState: DefinitionState,
    val recurrence: Recurrence? = null,
    // NON-NULL, defaulting to Rolling: an absent wire/column token is not "unknown", it IS Rolling —
    // the backend's `#[default]` — so there is no third state for this field to be in. See [CadenceMode].
    val cadenceMode: CadenceMode = CadenceMode.Rolling,
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
