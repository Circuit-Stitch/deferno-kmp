@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * Everything an [Item] has **before any plugin loads** (ADR-0055): identity, owning org, title, tree
 * position, and sync bookkeeping. Everything else is a plugin. A field belongs here if the row would
 * still carry it saying nothing about when, how often, how strongly, or what became of it — [title]
 * qualifies, a description does not. All four kinds declare these fields identically today, and the
 * parity recipes copy them across untouched in both directions.
 *
 * Ids are raw UUID strings: the forest nests children under parents of *any* kind, so a kind-typed id
 * cannot express a tree edge, and the per-kind models wrap the same wire UUID in
 * `TaskId`/`HabitId`/`ChoreId`/`EventId`. [ownerOrgId] keeps its type — it is an ownership boundary,
 * not a tree edge. [id] is the stable UUID and the reconcile key; [ref] (`{org_slug}-{sequence}`) is
 * the human-facing reference, `null` on a just-created row, and never identity.
 */
@ObjCName("PluginCore")
data class Core(
    val id: String,
    /** The short org slug (`u-e4h2qk`) that [ref] is built from. Tenancy, and never optional. */
    val orgSlug: String,
    val title: String,
    /** Tree position: the id of the parent row, of any kind. `null` at a forest root. */
    val parentId: String? = null,
    /**
     * Tree position: the ids of this row's children, of any kind. Empty on the three recurring kinds,
     * because only the Task wire carries the array; the parity recipes leave it empty there rather
     * than deriving one.
     */
    val childIds: List<String> = emptyList(),
    /**
     * Tree position: the server-computed subtree progress a collapsed node's badge renders — done and
     * total descendants. Never re-derived client-side, because the `/items` snapshot is windowed and
     * counting the rows this device holds would give a different answer. `null` on a source that omits
     * them (a `/tasks/{id}` detail) and on the recurring kinds, which the snapshot does not compute.
     */
    val descendantDone: Long? = null,
    val descendantTotal: Long? = null,
    val sequence: Long? = null,
    val ref: String? = null,
    val dateCreated: Instant,
    /** Soft-delete tombstone. Non-null marks the row deleted; the row itself stays. */
    val deletedAt: Instant? = null,
    /** Whether this row holds a list summary or a fully fetched record. */
    val hydration: HydrationState = HydrationState.Summary,
    /** The owning org. Full-only enrichment — `null` on a summary row. */
    val ownerOrgId: OrgId? = null,
) {
    /** Whether this row is a soft-delete tombstone (`deleted_at` present). */
    val isDeleted: Boolean get() = deletedAt != null
}
