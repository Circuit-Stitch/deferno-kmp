package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState

/**
 * The narrow write seam the Tasks detail drives to move a Task across its working states (#73). It is
 * deliberately smaller than the whole `CommandExecutor`: the feature layer needs exactly one verb —
 * "set this Task to that working state" — so it depends on this interface, not on the command registry
 * directly, keeping the component testable on a recording fake (the shell supplies the real
 * implementation over `CommandExecutor`, ADR-0007).
 *
 * **Offline-first (ADR-0001).** The implementation maps [target] to the one lifecycle command that
 * reaches it ([com.circuitstitch.deferno.core.domain.command.taskCommandFor]) and dispatches it through
 * the executor: optimistic local apply + outbox enqueue, gated by the pre-flight applicability check
 * against [current]. A stale transition (the state the Task is already in) is a clean no-op, not a write.
 */
fun interface WorkingStateEditor {

    /**
     * Move [id] to [target]. [current] is the cached row used for the pre-flight enablement gate (so a
     * transition the Task is already in is rejected before any write); `null` skips the gate — an
     * uncached row is never blocked (mirrors the executor's offline-never-blocked rule).
     */
    suspend fun setWorkingState(id: TaskId, target: WorkingState, current: Task?)

    companion object {
        /** The default no-op editor, so the detail's repository-only constructor stays test-friendly. */
        val NONE: WorkingStateEditor = WorkingStateEditor { _, _, _ -> }
    }
}
