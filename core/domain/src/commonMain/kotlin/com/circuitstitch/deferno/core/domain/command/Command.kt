package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The shared **command registry** (ADR-0007, #26): the **single binding surface** every input
 * modality converges on — keyboard shortcuts, context menus (right-click / long-press),
 * drag-and-drop, the AI agent, and OS intents (Android App Actions, iOS App Intents / Siri) — defined
 * and tested once in the core. A `Command` names *what the user wants done* and carries its operands.
 *
 * **Pure data, dispatched — never self-executing (mirrors the outbox `Mutation`).** A `Command` holds
 * **no injected dependency** and has **no `execute()` of its own**; the one side-effecting site is
 * [CommandExecutor], which routes each command to a `core:data` write seam
 * ([com.circuitstitch.deferno.core.data.task.TaskWriter] / [com.circuitstitch.deferno.core.data.plan.PlanWriter]
 * — offline-first: optimistic local apply + outbox enqueue, ADR-0001). This is the exact shape the
 * write path already chose one layer down (`Mutation` is pure data; `OutboxTaskWriter` is the sole
 * imperative seam), so the two read identically. Keeping a command pure data makes it equatable,
 * trivially unit-testable, and **serialization-shaped** — an agent or OS-intent payload can reconstruct
 * and echo *exactly* what it ran.
 *
 * **The registry sits ABOVE the writers — a command maps to a writer call, it does not replace them.**
 * Each command's binding to its action lives in [CommandExecutor]'s exhaustive `when`; the enumerable
 * catalog + per-state applicability live on [CommandKind].
 *
 * **`CreateTask` is intentionally absent.** Offline-create is deferred for the *same* reason it is
 * absent from `Mutation`: at envelope v0.1 there is no server idempotency key / client-id echo to
 * reconcile a client-temp id against the server's assigned UUID, so a replayed create would duplicate
 * (ADR-0001 reconciles on `id`). Every command here targets an **existing** entity (a stable server
 * id), which is what makes its replay reconcile-clean. `CreateTask` joins the registry as a real
 * command the day the outbox gains a create intent — modelling it as a non-dispatchable stub today
 * would lie to every enumerating surface (palette / agent / OS intent).
 *
 * **Reparent / move is likewise absent.** ADR-0007 pairs "drag-to-reorder / reparent" as a v1 input
 * goal; the reorder half is bound ([ReorderPlan]), but moving a Task under a new parent has no write
 * seam yet (`TaskWriter` / `Mutation` carry no reparent intent), and the catalog stays 1:1 with the
 * seam — so it joins when the seam does, not as an unbacked command.
 */
sealed interface Command {
    /** The stable, platform-neutral catalog entry a palette / menu / agent / OS intent binds to. */
    val kind: CommandKind
}

/** A [Command] addressing a single existing [com.circuitstitch.deferno.core.model.Task] (mirrors `TaskMutation`). */
sealed interface TaskCommand : Command {
    val taskId: TaskId
}

/** A [Command] addressing one day's plan ordering for `(date, tz)` (mirrors `PlanMutation`). */
sealed interface PlanCommand : Command {
    val date: LocalDate
    val tz: String
}

// --- Status: semantic lifecycle verbs (the agent / keyboard / Siri vocabulary ADR-0007 names) ---
//
// Each verb is the ONLY command reaching its transition, so there is no "two ways to do the same
// thing" (there is deliberately no generic SetWorkingState). They differ from each other precisely in
// their [CommandKind.enabledFor] rule, and each encodes a design principle: completion is a clean
// finish, Reopen is the "Done is never a dead end" guarantee, Drop is the non-punitive "let it go"
// (docs/design-principles.md). The intermediate transitions (Start → InProgress, SendToReview →
// InReview) are deferred until a board/kanban consumer needs them — they add non-breakingly.

/** Mark the Task done (`→ WorkingState.Done`). Disabled when it is already Done. */
data class CompleteTask(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.CompleteTask
}

/** Reopen a finished Task (`→ WorkingState.Open`) — the forgiving "Done is never a dead end" path. */
data class ReopenTask(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.ReopenTask
}

/** Drop a Task (`→ WorkingState.Dropped`) — non-punitively let it go. Disabled when already Dropped. */
data class DropTask(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.DropTask
}

// --- Edit / schedule / organize: orthogonal, 1:1 with the writer's parameterized fields ---

/** Rename the Task. */
data class RenameTask(override val taskId: TaskId, val title: String) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.RenameTask
}

/** Set the Task's deadline. */
data class SetTaskDeadline(override val taskId: TaskId, val completeBy: Instant) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.SetTaskDeadline
}

/** Clear the Task's deadline (distinct from setting one — the writer emits an explicit `null`). */
data class ClearTaskDeadline(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.ClearTaskDeadline
}

/** Set the Task's description body. */
data class SetTaskDescription(override val taskId: TaskId, val description: String) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.SetTaskDescription
}

/** Clear the Task's description. */
data class ClearTaskDescription(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.ClearTaskDescription
}

/** Replace the Task's labels (an empty list clears them — the field is always present). */
data class SetTaskLabels(override val taskId: TaskId, val labels: List<String>) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.SetTaskLabels
}

/**
 * Pin or unpin the Task. A single boolean toggle, **not** split into Pin/Unpin verbs: pinning carries
 * no distinct product semantics (unlike the status verbs), so the Pin-vs-Unpin *label* is a
 * presentation choice the binding layer makes from the current value — one command, two affordances.
 */
data class SetTaskPinned(override val taskId: TaskId, val pinned: Boolean) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.SetTaskPinned
}

/**
 * Soft-delete the Task (reversible — the server tombstones it). Flagged [CommandKind.destructive] so a
 * binding surface can confirm / offer undo (docs/design-principles.md: forgiving, no destructive
 * surprises). It carries **no timestamp** by design: the optimistic tombstone's `deletedAt` is the
 * writer's injected clock (`OutboxTaskWriter` owns `now`), which keeps the command deterministic and
 * serialization-shaped — do not add one here.
 */
data class DeleteTask(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.DeleteTask
}

// --- Plan: 1:1 with PlanWriter ---

/** Add the Task to the `(date, tz)` plan (idempotent — a no-op if already present). */
data class AddToPlan(val taskId: TaskId, override val date: LocalDate, override val tz: String) : PlanCommand {
    override val kind: CommandKind get() = CommandKind.AddToPlan
}

/** Remove the Task from the `(date, tz)` plan. */
data class RemoveFromPlan(val taskId: TaskId, override val date: LocalDate, override val tz: String) : PlanCommand {
    override val kind: CommandKind get() = CommandKind.RemoveFromPlan
}

/** Set the `(date, tz)` plan to exactly [taskIds] in order — the drag-drop result (mirrors `PlanReorder`). */
data class ReorderPlan(val taskIds: List<TaskId>, override val date: LocalDate, override val tz: String) : PlanCommand {
    override val kind: CommandKind get() = CommandKind.ReorderPlan
}

/**
 * The outcome of [CommandExecutor.execute] — the honest, **offline-first** vocabulary (ADR-0001).
 *
 * There is deliberately no `Success`/`Confirmed`: a write is applied to the local cache and **enqueued**
 * for replay, so the only truthful word is [Accepted] — *not* "saved on the server." A binding surface
 * (an agent or Siri especially) must report "queued, it'll sync," never a false server confirmation.
 * Both arms carry the [CommandKind] so the caller can report *what* it did without re-inspecting the
 * command. Extensible by adding sealed members.
 */
sealed interface CommandResult {
    val kind: CommandKind

    /** Optimistically applied to the local cache + enqueued to the outbox. **Not** server-confirmed. */
    data class Accepted(override val kind: CommandKind) : CommandResult

    /**
     * Refused by the pure pre-flight gate ([CommandKind.enabledFor]) **before any write** — the gentle,
     * structured, no-exception path for a command a stale menu offered but the current state forbids
     * (e.g. [CompleteTask] on an already-Done Task).
     */
    data class Rejected(override val kind: CommandKind, val reason: RejectionReason) : CommandResult
}

/** Why a [CommandResult.Rejected] command was refused. */
enum class RejectionReason {
    /** The command does not apply to the target's current state ([CommandKind.enabledFor] returned false). */
    NotApplicable,
}
