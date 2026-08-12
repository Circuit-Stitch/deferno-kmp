@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.ExternalRef
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Items this one cannot be worked until they resolve, plus the two **server-derived** readiness flags
 * that come back with them (ADR-0034, #289).
 *
 * The flags are read-only truth and are carried rather than re-derived: [blocked] is `true` when
 * this item has an unresolved blocker *or* an ancestor is blocked (the flag inherits down the tree
 * across kinds), and [isBlocker] when it gates at least one other. The client has never computed the
 * readiness rules and this plugin does not start — deriving [blocked] from [blockedBy] would be
 * wrong for exactly the inherited case, where the edges are on an ancestor and this row has none.
 *
 * [blockedBy] is Task-only: edges are Task-held on the wire, and a recurring definition always
 * carries the flags with an empty edge list. It is also empty on a summary row that has the flags,
 * which is the second reason the flags cannot be recomputed from it.
 */
@ObjCName("PluginBlocker")
data class Blocker(
    val blocked: Boolean = false,
    val isBlocker: Boolean = false,
    val blockedBy: List<BlockedByRef> = emptyList(),
) : Linkage {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Blocker()
}

/**
 * The item this one hands off to — today's `Task.nextTaskId`, a single forward edge.
 *
 * Named for the relation rather than the field because the reference model's `Succeeds` is the one
 * mechanism that will serve series segmentation, follow-ups and coreference. This client has only
 * the one edge on the wire, so that is all this carries; widening it to a list of typed relations is
 * a target-recipe change and needs a wire field to hold it.
 */
@ObjCName("PluginSucceeds")
data class Succeeds(val nextId: String? = null) : Linkage {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Succeeds()
}

/**
 * Provenance for an item mirrored from an upstream system — a GitHub-imported issue today.
 *
 * Drives the row's source mark, the dimmed `[GitHub#N]` ref prefix and the detail Source cell.
 * `null` for a native Deferno item, which is the common case and the degenerate value.
 */
@ObjCName("PluginImportable")
data class Importable(val external: ExternalRef? = null) : Linkage {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Importable()
}
