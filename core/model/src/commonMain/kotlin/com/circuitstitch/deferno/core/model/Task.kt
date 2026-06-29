package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * A Task as the rest of the app sees it — the clean domain projection of the wire `task` DTO
 * (ADR-0011), persisted in and observed from the local database (ADR-0001). The UI reads `Task`
 * from repository `Flow`s only, never the network.
 *
 * **Identity vs reference.** [id] is the stable UUID and the reconcile key. [ref]
 * (`{org_slug}-{sequence}`) is the human-facing reference and can be `null` on a row the server
 * has only just created (the staging `/tasks/plan` payload omits `ref`/`sequence` for brand-new
 * entries), so it is nullable and never used as identity.
 *
 * **Hydration.** [hydration] records whether this is a list [HydrationState.Summary] or a fully
 * fetched [HydrationState.Full] row. The full-only enrichment fields ([description], [nextTaskId],
 * [ownerOrgId]) are `null` until the Task is hydrated; reconcile from a summary snapshot must not
 * clobber them on an already-full row (#22).
 *
 * **Tombstone.** A non-null [deletedAt] marks a soft-deleted row (ADR-0001 LWW); [isDeleted]
 * is the read helper. The cache keeps tombstoned rows so a full-snapshot reconcile is idempotent.
 */
data class Task(
    val id: TaskId,
    val orgSlug: String,
    val title: String,
    val workingState: WorkingState,
    val labels: List<String> = emptyList(),
    val parentId: TaskId? = null,
    val children: List<TaskId> = emptyList(),
    val completeBy: Instant? = null,
    // The deadline's clock time (#348). `completeBy` carries the day; this carries "HH:MM" within it.
    // `null` = no time-of-day (all-day / end-of-day deadline). Wire `deadline_time_of_day`.
    val deadlineTimeOfDay: LocalTime? = null,
    val productive: Double? = null,
    val desire: Double? = null,
    val pinned: Boolean = false,
    val sequence: Long? = null,
    val ref: String? = null,
    val dateCreated: Instant,
    val finishedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val hydration: HydrationState = HydrationState.Summary,
    // Full-only enrichment — populated when [hydration] == [HydrationState.Full].
    val ownerOrgId: OrgId? = null,
    val description: String? = null,
    val nextTaskId: TaskId? = null,
    // Server-computed subtree progress carried by the `/items` snapshot (ADR-0034): done / total
    // descendants, for a collapsed tree node's progress badge. `null` when the source omits them
    // (e.g. a `/tasks/{id}` detail). Not re-derived client-side — the server windows the snapshot.
    val descendantDone: Long? = null,
    val descendantTotal: Long? = null,
    // Server-derived dependency state (ADR-0034, #289), read-only truth — never re-derived client-side.
    // [blocked]: an unresolved blocker or a blocked ancestor (inherits down the tree). [isBlocker]: this
    // Task gates at least one other. [blockedBy]: the ordered edges on the full record (empty on a
    // summary/cached row). Both flags default `false` so a payload omitting them decodes cleanly.
    val blocked: Boolean = false,
    val isBlocker: Boolean = false,
    val blockedBy: List<BlockedByRef> = emptyList(),
    // External provenance for a GitHub-imported issue (or other synced item): drives the source mark, the
    // `[GitHub#N]` ref prefix, and the detail Source cell. `null` for a native Deferno Task. Set from the
    // `/items` snapshot + the `/tasks/{id}` detail (both carry the wire `external` block); like [blocked]
    // it is not on the list/summary path.
    val external: ExternalRef? = null,
    // Backend-hosted attachment rollup (#311), derived from the `attachments` array on the `/items`
    // snapshot + `/tasks/{id}` detail (the array used to be dropped). [attachmentCount] is the number of
    // files; [attachmentTotalSize] their summed bytes. Both `0` when the source has none / on the
    // summary path. Power offline "has attachment" search + attachment-size sort (ADR-0042). Task-only
    // for now — recurring kinds carry no attachment metadata yet.
    val attachmentCount: Int = 0,
    val attachmentTotalSize: Long = 0,
) {
    /** Whether this row is a soft-delete tombstone (`deleted_at` present). */
    val isDeleted: Boolean get() = deletedAt != null

    /** Whether this Task has at least one backend-hosted attachment (#311). */
    val hasAttachment: Boolean get() = attachmentCount > 0
}
