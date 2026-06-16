package com.circuitstitch.deferno.feature.tasks

/**
 * The narrow write seam the Tasks Item tree drives for the modal Move (ADR-0034 #228) — the cross-kind
 * sibling of [WorkingStateEditor]. The tree needs exactly one verb ("move this item under that parent at
 * that index"), so it depends on this fun-interface rather than the whole `CommandExecutor`, staying
 * testable on a recording fake (the shell supplies the real implementation over `CommandExecutor`,
 * issuing a `MoveItem` command — optimistic apply + outbox enqueue, ADR-0001/0007).
 */
fun interface MoveEditor {

    /** Move [id] under [newParentId] (`null` = root) to insertion index [position] among its children. */
    suspend fun move(id: String, newParentId: String?, position: Int)

    companion object {
        /** The default no-op editor, so the tree's read/navigation-only tests construct without it. */
        val NONE: MoveEditor = MoveEditor { _, _, _ -> }
    }
}
