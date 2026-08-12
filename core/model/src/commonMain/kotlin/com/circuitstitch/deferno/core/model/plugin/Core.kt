package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import kotlin.time.Instant

/**
 * Everything an [Item] has **before any plugin loads** (ADR-0055): identity, owning org, title, tree
 * position, and sync bookkeeping. Everything else is a plugin.
 *
 * The membership rule is "would this still be here if the item said nothing at all about when, how
 * often, how strongly, or what became of it?". That is why [title] is here and a description is not:
 * a row with no title is not addressable, a row with no description is ordinary.
 *
 * ### Every field here is on all four of today's kinds
 *
 * `Task`, `Habit`, `Chore` and `Event` each declare all ten of these identically — they are ten of
 * the 24 fields the `DefernoPlugins` experiment measured as declared four times over. Core is where
 * that repetition stops, and the parity recipes (#418) copy these across untouched in both
 * directions rather than mapping them.
 *
 * ### Ids are raw UUID strings, deliberately
 *
 * The same reasoning as `Item.id` on the kind-blind projection: the forest nests children under
 * parents of *any* kind, so a kind-typed id cannot express a tree edge. The per-kind models wrap the
 * same wire UUID in `TaskId`/`HabitId`/`ChoreId`/`EventId`; this is the string they all unwrap to.
 * [ownerOrgId] keeps its type because it is not a tree edge — it is the ADR-0002 ownership boundary.
 *
 * ### Identity versus reference
 *
 * [id] is the stable UUID and the reconcile key. [ref] (`{org_slug}-{sequence}`) is the human-facing
 * reference, `null` on a row the server has only just created, and never used as identity.
 */
data class Core(
    val id: String,
    /** The short org slug (`u-e4h2qk`) that [ref] is built from. Tenancy, and never optional. */
    val orgSlug: String,
    val title: String,
    /** Tree position: the id of the parent row, of any kind. `null` at a forest root. */
    val parentId: String? = null,
    /**
     * Tree position: the ids of this row's children, of any kind.
     *
     * Empty on the three recurring kinds because only the Task wire carries the array today — which
     * is a fact about the wire, not about the tree, so it is not a reason to shape Core around it.
     */
    val childIds: List<String> = emptyList(),
    val sequence: Long? = null,
    val ref: String? = null,
    val dateCreated: Instant,
    /** Soft-delete tombstone (ADR-0001 LWW). Non-null marks the row deleted; the row itself stays. */
    val deletedAt: Instant? = null,
    /** Whether this row holds a list summary or a fully fetched record (ADR-0001, #22). */
    val hydration: HydrationState = HydrationState.Summary,
    /** The ADR-0002 owning org. Full-only enrichment — `null` on a summary row. */
    val ownerOrgId: OrgId? = null,
) {
    /** Whether this row is a soft-delete tombstone (`deleted_at` present). */
    val isDeleted: Boolean get() = deletedAt != null
}
