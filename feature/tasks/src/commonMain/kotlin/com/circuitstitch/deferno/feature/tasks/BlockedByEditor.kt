package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.data.task.BlockedByResult
import com.circuitstitch.deferno.core.model.TaskId

/**
 * The tree menu's dependency-edge write seam (#291), threaded from the shell over the command
 * executor (`SetTaskBlockedBy`) like [WorkingStateEditor] — the feature layer never touches the
 * registry. Unlike the offline-first editors it **returns the verdict** ([BlockedByResult], the
 * online-only convert posture): the component surfaces an Offline / Rejected outcome on its state so
 * the View can tell the user why the edit snapped back.
 */
fun interface BlockedByEditor {

    /** Replace [id]'s ordered blocker list with the raw item ids [blockers] (empty clears them all). */
    suspend fun setBlockedBy(id: TaskId, blockers: List<String>): BlockedByResult

    companion object {
        /** The inert default the read/move-only tests construct the component with. */
        val NONE: BlockedByEditor = BlockedByEditor { _, _ -> BlockedByResult.Applied }
    }
}
