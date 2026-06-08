package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.jvm.JvmInline

/**
 * A stable, refactor-proof binding token for a command **kind** (ADR-0007). It is the contract an AI
 * tool-schema, an OS-intent registry (Android App Actions / iOS App Intents), telemetry, and a command
 * palette all key on — distinct from the Kotlin type name (which is free to be renamed) and from a
 * [Command] *instance* (which carries operands). **Never change an existing [value]:** it is the public
 * identity those external surfaces have bound to.
 *
 * It is a UI/agent binding token, **not** a wire route — deliberately independent of the outbox
 * endpoint paths (`tasks/{id}`, `tasks/plan/add`, …). The two namespaces evolve separately.
 */
@JvmInline
value class CommandId(val value: String) {
    init {
        require(value.isNotBlank()) { "CommandId must not be blank" }
    }
}

/** Coarse grouping a palette / context menu uses to section the catalog. */
enum class CommandCategory {
    /**
     * Lifecycle transitions across the five [WorkingState]s — [CommandKind.OpenTask] /
     * [CommandKind.StartTask] / [CommandKind.SendTaskToReview] / [CommandKind.CompleteTask] /
     * [CommandKind.ReopenTask] / [CommandKind.DropTask].
     */
    Status,

    /** Content edits (rename, description, delete). */
    Edit,

    /** Time-bound scheduling (the deadline). */
    Schedule,

    /** Organization (labels, pinning). */
    Organize,

    /** Daily-plan membership + ordering. */
    Plan,
}

/**
 * The enumerable **catalog** of every bindable command (ADR-0007) — what a command palette lists, a
 * context menu filters, and the agent / OS-intent layer maps natural-language verbs onto. The enum
 * *is* the catalog (its [entries]), so there is one source of truth with nothing to drift; each kind
 * carries its stable [id], coarse [category], and a [destructive] safety flag (which drives confirm /
 * undo affordances — the one piece of safety metadata that is genuinely cross-platform and
 * value-aligned, docs/design-principles.md: forgiving, no destructive surprises).
 *
 * **Deferred to the platform binding layer:** human titles / i18n (localization is platform-owned;
 * `core:domain` has no localization seam), keyboard shortcuts (OS-specific — Ctrl vs Cmd — and ADR-0007
 * places them at the platform input layer), and OS-intent verb strings. Those layers bind to the
 * stable [id]; the catalog stays free of presentation.
 */
enum class CommandKind(
    val id: CommandId,
    val category: CommandCategory,
    val destructive: Boolean = false,
) {
    CompleteTask(CommandId("task.complete"), CommandCategory.Status),
    ReopenTask(CommandId("task.reopen"), CommandCategory.Status),
    DropTask(CommandId("task.drop"), CommandCategory.Status),
    RenameTask(CommandId("task.rename"), CommandCategory.Edit),
    SetTaskDeadline(CommandId("task.set-deadline"), CommandCategory.Schedule),
    ClearTaskDeadline(CommandId("task.clear-deadline"), CommandCategory.Schedule),
    SetTaskDescription(CommandId("task.set-description"), CommandCategory.Edit),
    ClearTaskDescription(CommandId("task.clear-description"), CommandCategory.Edit),
    SetTaskLabels(CommandId("task.set-labels"), CommandCategory.Organize),
    SetTaskPinned(CommandId("task.set-pinned"), CommandCategory.Organize),
    DeleteTask(CommandId("task.delete"), CommandCategory.Edit, destructive = true),
    AddToPlan(CommandId("plan.add"), CommandCategory.Plan),
    RemoveFromPlan(CommandId("plan.remove"), CommandCategory.Plan),
    ReorderPlan(CommandId("plan.reorder"), CommandCategory.Plan),

    // The two intermediate lifecycle verbs (#73): the interactive Tasks detail moves a Task across all
    // five working states, so the catalog now binds Start (→ InProgress) and SendToReview (→ InReview)
    // alongside the existing Complete/Reopen/Drop. Appended at the end (CommandIds are a public contract
    // — never reorder/rename existing entries); each, like the other status verbs, is the ONLY command
    // reaching its transition (no generic SetWorkingState, Command.kt's "no two ways" note).
    StartTask(CommandId("task.start"), CommandCategory.Status),
    SendTaskToReview(CommandId("task.send-to-review"), CommandCategory.Status),

    // The general "set back to Open" verb (#73 follow-up): the interactive five-state editor must be able
    // to move a Task to Open from ANY non-Open state — including the non-terminal In-progress / In-review.
    // [ReopenTask] alone can't serve that chip: its enabledFor is terminal-only, so the Open chip from a
    // non-terminal state produced a command the executor rejected NotApplicable — a silent no-op. OpenTask
    // is enabled whenever the Task isn't already Open; ReopenTask stays the narrower terminal-only verb.
    // Appended at the end (CommandIds are a public contract — never reorder/rename existing entries).
    OpenTask(CommandId("task.open"), CommandCategory.Status),
    ;

    /**
     * Pure applicability over a Task's **current** state — so a context menu / palette can grey out a
     * command that cannot run (Complete on a Done Task; Reopen only when terminal) and the executor can
     * reject a stale command, **without re-deriving the [WorkingState] transition matrix on every
     * platform**. Re-implementing this per platform is exactly the duplication the single-binding-surface
     * decision exists to kill — so it lives here, "tested once in the core" (ADR-0007), reusing the
     * model's own [WorkingState.isTerminal] / [Task.isDeleted].
     *
     * [task] is the cached row for this command's target. A `null` task (unknown / not yet cached)
     * returns `true`: an offline write to an uncached row must never be blocked — it still enqueues and
     * reconciles on replay, matching `OutboxTaskWriter`'s "absent row still enqueues" behaviour.
     *
     * Edit / schedule / organize / plan kinds always apply (a clear or a relabel is valid in any
     * state; plan membership is a list concern the caller already knows), so only the status verbs and
     * the destructive delete carry a real rule.
     */
    fun enabledFor(task: Task?): Boolean = when (this) {
        OpenTask -> task == null || task.workingState != WorkingState.Open
        StartTask -> task == null || task.workingState != WorkingState.InProgress
        SendTaskToReview -> task == null || task.workingState != WorkingState.InReview
        CompleteTask -> task == null || task.workingState != WorkingState.Done
        ReopenTask -> task == null || task.workingState.isTerminal
        DropTask -> task == null || task.workingState != WorkingState.Dropped
        DeleteTask -> task == null || !task.isDeleted
        else -> true
    }

    companion object {
        /** The catalog a Task context menu / palette section offers (every non-plan kind). */
        val taskKinds: List<CommandKind> get() = entries.filter { it.category != CommandCategory.Plan }

        /** The catalog a Plan view offers. */
        val planKinds: List<CommandKind> get() = entries.filter { it.category == CommandCategory.Plan }
    }
}
