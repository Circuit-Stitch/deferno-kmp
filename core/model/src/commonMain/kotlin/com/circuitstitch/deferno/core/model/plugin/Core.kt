@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * Everything an [Item] has **before any plugin loads** (ADR-0055): identity, owning org, title, tree
 * position, and sync bookkeeping. Everything else is a plugin.
 *
 * The membership rule is "would this still be here if the item said nothing at all about when, how
 * often, how strongly, or what became of it?". That is why [title] is here and a description is not:
 * a row with no title is not addressable, a row with no description is ordinary.
 *
 * ### Almost every field here is declared four times over today
 *
 * `Task`, `Habit`, `Chore` and `Event` each declare [id], [orgSlug], [title], [parentId],
 * [sequence], [ref], [dateCreated], [deletedAt], [hydration] and [ownerOrgId] identically — ten of
 * the 24 fields the `DefernoPlugins` experiment measured as repeated across all four structs. Core
 * is where that repetition stops, and the parity recipes (#418) copy them across untouched in both
 * directions rather than mapping them.
 *
 * The two exceptions are [childIds] and the [descendantDone]/[descendantTotal] pair, which only the
 * Task wire carries. They are here because of what they *are* — tree position, which Core owns — and
 * not because of how many kinds happen to ship them; each says in its own KDoc why the recurring
 * kinds leave it at its degenerate value.
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
@ObjCName("PluginCore")
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
    /**
     * Tree position: the server-computed subtree progress a collapsed node's badge renders — done
     * and total descendants (ADR-0049).
     *
     * Core rather than a plugin because they are a rollup **over the tree**, and the tree is what
     * Core owns. Never re-derived client-side: the `/items` snapshot is windowed, so counting the
     * rows this device happens to hold would give a different and quieter-wrong answer. `null` on a
     * source that omits them — a `/tasks/{id}` detail — and on the recurring kinds, which the
     * snapshot does not compute them for.
     */
    val descendantDone: Long? = null,
    val descendantTotal: Long? = null,
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
