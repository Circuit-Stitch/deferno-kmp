@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.ExternalRef
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Items this one cannot be worked until they resolve, plus the two **server-derived** readiness flags
 * that come back with them (ADR-0034). The flags are carried, never re-derived: [blocked] is `true`
 * when this item has an unresolved blocker *or* an ancestor is blocked — it inherits down the tree
 * across kinds — and [isBlocker] when it gates at least one other. Deriving [blocked] from
 * [blockedBy] would be wrong for the inherited case, where the edges sit on an ancestor and this row
 * has none. [blockedBy] is Task-only (edges are Task-held on the wire) and is also empty on a summary
 * row that has the flags.
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
 * The item this one hands off to — today's `Task.nextTaskId`, a single forward edge. Named for the
 * relation, not the field: the same mechanism serves series segmentation, follow-ups and coreference
 * once a wire field can hold a list of typed relations.
 */
@ObjCName("PluginSucceeds")
data class Succeeds(val nextId: String? = null) : Linkage {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Succeeds()
}

/**
 * Provenance for an item mirrored from an upstream system — a GitHub-imported issue today. Drives the
 * row's source mark, the dimmed `[GitHub#N]` ref prefix and the detail Source cell. `null` for a
 * native Deferno item, the common case and the degenerate value.
 */
@ObjCName("PluginImportable")
data class Importable(val external: ExternalRef? = null) : Linkage {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Importable()
}
