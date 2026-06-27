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

    /** New-item creation + post-creation kind conversion ([CommandKind.CreateItem] / [CommandKind.ConvertItem]). */
    Create,

    /** Acting on one dated firing from the Calendar — mark / clear / reschedule ([CommandKind.MarkOccurrence] …). */
    Occurrence,

    /** The Active Account's User-setting writes ([CommandKind.SetTheme] …) — the backed Settings categories (#173). */
    Settings,

    /** Cross-kind tree move — reparent + reorder ([CommandKind.MoveItem], ADR-0034 #228). */
    Move,

    /** Recurring-definition state — the Habit/Chore/Event "light switch" ([CommandKind.SetDefinitionState], #299). */
    Definition,
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
    val onlineOnly: Boolean = false,
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

    // Create is OFFLINE-FIRST (#185, ADR-0001 forward path from ADR-0016): the backend now dedupes a
    // create on the client-supplied id (Kyle-Falconer/Deferno#402), so it rides the outbox like every
    // edit — optimistic local insert + enqueue — and is NOT onlineOnly. Convert stays ONLINE-ONLY: it
    // mutates an existing item's kind with no client-id idempotency story, so its [onlineOnly] flag is
    // still the signal the agent / OS-intent layer reads to surface the connectivity requirement.
    CreateItem(CommandId("item.create"), CommandCategory.Create),
    ConvertItem(CommandId("item.convert"), CommandCategory.Create, onlineOnly = true),

    // Acting on a Calendar firing (#74): mark / clear / reschedule one Occurrence. Offline-first (these
    // target an existing firing — unlike create), so NOT onlineOnly. Appended at the end (CommandIds are a
    // public contract — never reorder/rename existing entries). The per-kind action set (a habit has no
    // start/skip; reschedule is Events-only) is gated by the View, not here, so these always apply.
    MarkOccurrence(CommandId("occurrence.mark"), CommandCategory.Occurrence),
    ClearOccurrence(CommandId("occurrence.clear"), CommandCategory.Occurrence),
    RescheduleOccurrence(CommandId("occurrence.reschedule"), CommandCategory.Occurrence),

    // User-setting writes (#173): the Settings Destination's backed categories route through the
    // registry like every other write — per-field verbs, 1:1 with the `SettingsWriter` seam (the
    // granularity settled on the issue; matches RenameTask/SetTaskDeadline's grain, never one
    // PatchSettings). Offline-first (optimistic apply + outbox enqueue, ADR-0001 — Appearance applies
    // live), so NOT onlineOnly; they target the Account's settings bag (no Task row), so no
    // [enabledFor] rule applies. Appended at the end (CommandIds are a public contract — never
    // reorder/rename existing entries).
    SetTheme(CommandId("settings.set-theme"), CommandCategory.Settings),
    SetTracking(CommandId("settings.set-tracking"), CommandCategory.Settings),
    SetDragAndDrop(CommandId("settings.set-drag-and-drop"), CommandCategory.Settings),
    SetDoneVisibility(CommandId("settings.set-done-visibility"), CommandCategory.Settings),

    // The cross-kind tree move (ADR-0034 #228): reparent + reorder one Item via the modal move mode /
    // keyboard, routed through the registry to the `ItemWriter` seam. Its own category (not Organize):
    // it is not a per-Task command (it addresses a raw cross-kind id) and routes to a distinct writer,
    // so it stays out of [taskKinds]. Appended at the end (CommandIds are a public contract — never
    // reorder/rename existing entries). Offline-first (optimistic apply + enqueue), so NOT onlineOnly;
    // it targets a tree node, not a Task row, so no [enabledFor] rule applies.
    MoveItem(CommandId("item.move"), CommandCategory.Move),

    // The recurring-definition "light switch" (#299): set a Habit/Chore/Event's DefinitionState, routed
    // through the registry to the `DefinitionWriter` seam. Its own category (not Status — that's the Task
    // lifecycle): it addresses a raw cross-kind id and routes to a distinct writer, so it stays out of
    // [taskKinds] (like MoveItem). Appended at the end (CommandIds are a public contract — never
    // reorder/rename existing entries). Offline-first (optimistic apply + enqueue), so NOT onlineOnly; it
    // targets a recurring definition, not a Task row, so no [enabledFor] rule applies.
    SetDefinitionState(CommandId("definition.set-state"), CommandCategory.Definition),
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
     * Edit / schedule / organize / plan / settings kinds always apply (a clear or a relabel is valid in
     * any state; plan membership is a list concern the caller already knows; a User-setting write has no
     * Task row at all, #173), so only the status verbs and the destructive delete carry a real rule.
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
        /** The catalog a Task context menu / palette section offers (the per-Task kinds only). */
        val taskKinds: List<CommandKind>
            get() = entries.filter {
                it.category != CommandCategory.Plan &&
                    it.category != CommandCategory.Create &&
                    it.category != CommandCategory.Occurrence &&
                    it.category != CommandCategory.Settings &&
                    it.category != CommandCategory.Move &&
                    it.category != CommandCategory.Definition
            }

        /** The catalog a Plan view offers. */
        val planKinds: List<CommandKind> get() = entries.filter { it.category == CommandCategory.Plan }

        /** The create + convert catalog the New surface / convert affordance offers (ADR-0016, #71). */
        val createKinds: List<CommandKind> get() = entries.filter { it.category == CommandCategory.Create }

        /** The catalog the Calendar day agenda offers for acting on a firing (#74). */
        val occurrenceKinds: List<CommandKind> get() = entries.filter { it.category == CommandCategory.Occurrence }

        /** The User-setting catalog the Settings Destination's backed categories drive (#173). */
        val settingsKinds: List<CommandKind> get() = entries.filter { it.category == CommandCategory.Settings }

        /** The cross-kind tree-move catalog the modal move mode / keyboard drives (ADR-0034 #228). */
        val moveKinds: List<CommandKind> get() = entries.filter { it.category == CommandCategory.Move }

        /** The recurring-definition state catalog the Item-tree command menu drives (#299). */
        val definitionKinds: List<CommandKind> get() = entries.filter { it.category == CommandCategory.Definition }
    }
}
