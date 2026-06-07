package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.plan.PlanWriter
import com.circuitstitch.deferno.core.data.task.TaskWriter
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState

/**
 * The single side-effecting dispatch site of the command registry (ADR-0007): it routes a pure-data
 * [Command] to exactly one `core:data` write seam via an **exhaustive `when`**. That exhaustiveness is
 * the binding guarantee the registry exists to provide — a new [Command] that nobody wired is a
 * *compile* error here, the same way [TaskWriter]'s [com.circuitstitch.deferno.core.data.task.OutboxTaskWriter]
 * is the one place that knows the local store + outbox. The status verbs ([CompleteTask] / [ReopenTask]
 * / [DropTask]) collapse onto [TaskWriter.setWorkingState] here — the executor is the one place that
 * still knows it was a "Complete", which is where future per-intent telemetry would hook.
 *
 * **Pre-flight gate.** Before any write, [execute] checks [CommandKind.enabledFor] against the supplied
 * cached row, so a command a stale menu offered (Complete on an already-Done Task) yields a clean
 * [CommandResult.Rejected] rather than a silent no-op PATCH. On dispatch it returns
 * [CommandResult.Accepted] — offline-first: optimistically applied + enqueued, **not** server-confirmed
 * (ADR-0001).
 *
 * **No DI annotations (yet).** It is a plain constructor-injected class, matching `OutboxTaskWriter` /
 * `OutboxPlanWriter` (which take their deps as bare constructor params). `core:di` is intentionally
 * empty in v1 and the writers are not `@ContributesBinding`'d yet, so wiring is a one-line,
 * non-breaking follow-up once the Account-scope graph (ADR-0008) lands — not this issue's scope.
 */
class CommandExecutor(
    private val taskWriter: TaskWriter,
    private val planWriter: PlanWriter,
) {
    /**
     * Run [command], first gating on [CommandKind.enabledFor].
     *
     * @param current the cached [Task] for the command's target, used only for the enablement
     *   pre-check. Callers that drive a Task command (menus) usually already hold it from the repository
     *   `Flow`; pass it to get the stale-command guard. `null` (the default, and the norm for plan
     *   commands) skips the gate — see [CommandKind.enabledFor] for why an uncached row is never blocked.
     */
    suspend fun execute(command: Command, current: Task? = null): CommandResult {
        if (!command.kind.enabledFor(current)) {
            return CommandResult.Rejected(command.kind, RejectionReason.NotApplicable)
        }
        when (command) {
            is CompleteTask -> taskWriter.setWorkingState(command.taskId, WorkingState.Done)
            is ReopenTask -> taskWriter.setWorkingState(command.taskId, WorkingState.Open)
            is DropTask -> taskWriter.setWorkingState(command.taskId, WorkingState.Dropped)
            is RenameTask -> taskWriter.rename(command.taskId, command.title)
            is SetTaskDeadline -> taskWriter.setDeadline(command.taskId, command.completeBy)
            is ClearTaskDeadline -> taskWriter.clearDeadline(command.taskId)
            is SetTaskDescription -> taskWriter.setDescription(command.taskId, command.description)
            is ClearTaskDescription -> taskWriter.clearDescription(command.taskId)
            is SetTaskLabels -> taskWriter.setLabels(command.taskId, command.labels)
            is SetTaskPinned -> taskWriter.setPinned(command.taskId, command.pinned)
            is DeleteTask -> taskWriter.delete(command.taskId)
            is AddToPlan -> planWriter.add(command.taskId, command.date, command.tz)
            is RemoveFromPlan -> planWriter.remove(command.taskId, command.date, command.tz)
            is ReorderPlan -> planWriter.reorder(command.taskIds, command.date, command.tz)
        }
        return CommandResult.Accepted(command.kind)
    }
}
