package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
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
 * **Create is offline-first ([CreateItem], #185); convert is still online-only ([ConvertItem]).** The
 * backend now dedupes a create on the client-supplied id (Kyle-Falconer/Deferno#402), so [CreateItem]
 * rides the outbox like every edit — optimistic local insert + enqueue, [CommandResult.Accepted]
 * carrying the client id — realizing ADR-0016's forward path. [ConvertItem] mutates an existing item's
 * kind with no client-id idempotency story, so it keeps the ADR-0016 gate: its [CommandKind.onlineOnly]
 * flag is the signal the agent / OS-intent layer reads, and the executor returns [CommandResult.Offline]
 * (not a false [CommandResult.Accepted]) when offline.
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
// Each verb encodes a design principle: Open sets the work back to the start, Start picks it up,
// SendToReview hands it on, completion is a clean finish, Reopen is the "Done is never a dead end"
// guarantee, Drop is the non-punitive "let it go" (docs/design-principles.md). The interactive Tasks
// detail (#73) maps each five-state affordance onto exactly one verb, so the UI never re-derives the
// transition matrix (ADR-0007).
//
// There is still no generic SetWorkingState(state) command: a verb's identity (its name + telemetry +
// agent/OS-intent vocabulary) is the point. [OpenTask] and [ReopenTask] both target `Open`, but they
// are NOT two ways to do the same thing — they differ in their [CommandKind.enabledFor] rule: OpenTask
// moves to Open from any non-Open state (what the working-state editor needs), while ReopenTask is the
// narrower, terminal-only "reopen a finished Task" affordance a context menu still surfaces on a
// Done/Dropped row. [taskCommandFor] maps the editor's Open chip onto OpenTask.

/** Pick the Task up (`→ WorkingState.InProgress`). Disabled when it is already In progress (#73). */
data class StartTask(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.StartTask
}

/** Hand the Task on for review (`→ WorkingState.InReview`). Disabled when it is already In review (#73). */
data class SendTaskToReview(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.SendTaskToReview
}

/** Mark the Task done (`→ WorkingState.Done`). Disabled when it is already Done. */
data class CompleteTask(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.CompleteTask
}

/**
 * Move the Task back to the start of its lifecycle (`→ WorkingState.Open`) from **any** non-Open
 * state (#73). This is the general "set it back to Open" verb the interactive five-state editor maps
 * the Open chip onto — disabled only when the Task is already Open.
 *
 * It is distinct from [ReopenTask]: that one is the narrower, forgiving "Done is never a dead end"
 * affordance, gated to a *terminal* (Done/Dropped) Task only. Keeping both means a context menu can
 * still surface "Reopen" specifically on a finished Task while the working-state editor offers a plain
 * "Open" from In-progress / In-review without the terminal-only gate swallowing it as a no-op.
 */
data class OpenTask(override val taskId: TaskId) : TaskCommand {
    override val kind: CommandKind get() = CommandKind.OpenTask
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

// --- Create (OFFLINE-FIRST, #185) + convert (ONLINE-ONLY, ADR-0016) ---
//
// Create now rides the outbox like every edit: the backend dedupes it on the client-supplied id
// (Kyle-Falconer/Deferno#402), so the writer mints a UUID, inserts optimistically, and enqueues — the
// executor reports Accepted (queued) carrying that id. Convert still requires connectivity (it mutates
// an existing item's kind with no client-id idempotency story), so it keeps the [CommandKind.onlineOnly]
// flag the executor maps to [CommandResult.Offline] when refused. The payload is the kind-specific
// create DTO the [com.circuitstitch.deferno.core.data.create.CreateWriter] consumes; a sealed
// [CreateItem.Payload] keeps the operands typed per kind and the dispatch exhaustive.

/** Create a new item of the explicitly-chosen [Payload] kind (ADR-0015 — no field-inference). */
data class CreateItem(val payload: Payload) : Command {
    override val kind: CommandKind get() = CommandKind.CreateItem

    /** The kind-specific create operands. Exactly one per picker kind (Task/Habit/Chore/Event). */
    sealed interface Payload {
        val itemKind: ItemKind

        data class Task(val payload: CreateTaskPayload) : Payload {
            override val itemKind: ItemKind get() = ItemKind.Task
        }

        data class Habit(val payload: CreateHabitPayload) : Payload {
            override val itemKind: ItemKind get() = ItemKind.Habit
        }

        data class Chore(val payload: CreateChorePayload) : Payload {
            override val itemKind: ItemKind get() = ItemKind.Chore
        }

        data class Event(val payload: CreateEventPayload) : Payload {
            override val itemKind: ItemKind get() = ItemKind.Event
        }
    }
}

/** Convert the existing item [itemId] (currently [fromKind]) to another kind via `POST /items/{id}/convert`. */
data class ConvertItem(
    val itemId: String,
    val fromKind: ItemKind,
    val payload: ConvertItemPayload,
) : Command {
    override val kind: CommandKind get() = CommandKind.ConvertItem
}

// --- Occurrence: acting on one dated firing from the Calendar (#74) — OFFLINE-FIRST ---
//
// Unlike create, these target an EXISTING firing (a recurring Habit/Chore/Event occurrence), so they
// ride the normal offline-first outbox (ADR-0001) — optimistic apply + enqueue. The operand is the
// local Calendar row id ([occurrenceItemId]); the writer resolves the firing's kind + series + date
// from the cached row, so the command stays a thin, serialization-shaped intent. 1:1 with the
// `OccurrenceWriter` seam.

/** A [Command] acting on one dated firing (an Occurrence), addressed by its Calendar row id (#74). */
sealed interface OccurrenceCommand : Command {
    val occurrenceItemId: String
}

/** Mark a firing with a coarse [action] (start / complete / skip) — the per-kind occurrence write. */
data class MarkOccurrence(override val occurrenceItemId: String, val action: OccurrenceAction) : OccurrenceCommand {
    override val kind: CommandKind get() = CommandKind.MarkOccurrence
}

/** Clear a firing's status back to Scheduled — the forgiving undo (design-principle #8). */
data class ClearOccurrence(override val occurrenceItemId: String) : OccurrenceCommand {
    override val kind: CommandKind get() = CommandKind.ClearOccurrence
}

/** Reschedule a firing to [newDate] (Events only in v1 — habit/chore reschedule is server-unimplemented). */
data class RescheduleOccurrence(override val occurrenceItemId: String, val newDate: LocalDate) : OccurrenceCommand {
    override val kind: CommandKind get() = CommandKind.RescheduleOccurrence
}

// --- Settings: the Active Account's UserSettings (#72, #173) — OFFLINE-FIRST, 1:1 with SettingsWriter ---
//
// Per-field verbs (the granularity settled on #173): each backed Settings category's write is its own
// command, matching the catalog's existing grain (RenameTask / SetTaskDeadline — never one PatchTask).
// They mirror the `SettingsWriter` seam 1:1 — and deliberately echo the outbox's `SettingsMutation`
// names (`core:data`'s SetTheme/SetTracking/…), so the registry, writer, and queue read identically.
// The target is the Account's single settings bag (no row id), so the sealed interface carries no
// operand of its own and [CommandKind.enabledFor] has no per-Task rule to apply — a settings write is
// valid in any state. Offline-first like the Task edits: optimistic local apply (Appearance applies
// LIVE — the same `Flow` drives the app-wide theme) + outbox enqueue of `PATCH /auth/me/settings`.

/** A [Command] writing one field-group of the Active Account's `UserSettings` (mirrors `SettingsMutation`). */
sealed interface SettingsCommand : Command

/** Set the appearance — theme family + mode (Appearance category). Applies live + persists. */
data class SetTheme(val family: ThemeFamily, val mode: ThemeMode) : SettingsCommand {
    override val kind: CommandKind get() = CommandKind.SetTheme
}

/** Toggle analytics/tracking (Data & Privacy category). */
data class SetTracking(val enabled: Boolean) : SettingsCommand {
    override val kind: CommandKind get() = CommandKind.SetTracking
}

/** Toggle the experimental drag-and-drop affordance (Task behavior category). */
data class SetDragAndDrop(val enabled: Boolean) : SettingsCommand {
    override val kind: CommandKind get() = CommandKind.SetDragAndDrop
}

/** Set the done-visibility windows in seconds (Task behavior category); `null` clears a window. */
data class SetDoneVisibility(val globalSeconds: Long?, val dashboardSeconds: Long?) : SettingsCommand {
    override val kind: CommandKind get() = CommandKind.SetDoneVisibility
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

    /**
     * Optimistically applied to the local cache + enqueued to the outbox. **Not** server-confirmed.
     *
     * [itemId] is the created item's id for [CreateItem] (the client-generated UUID, #185) / [ConvertItem]
     * (the converted item's id), and `null` for every edit (which targets an existing id, so there's
     * nothing new to surface). The Inbox accept reads it to attach the retained brain-dump recording to
     * the freshly-created Task (#211).
     */
    data class Accepted(override val kind: CommandKind, val itemId: String? = null) : CommandResult

    /**
     * Refused by the pure pre-flight gate ([CommandKind.enabledFor]) **before any write** — the gentle,
     * structured, no-exception path for a command a stale menu offered but the current state forbids
     * (e.g. [CompleteTask] on an already-Done Task).
     */
    data class Rejected(override val kind: CommandKind, val reason: RejectionReason) : CommandResult

    /**
     * An [CommandKind.onlineOnly] command ([CreateItem] / [ConvertItem]) refused because there is no
     * connectivity (ADR-0016): the create endpoint was **not** called and **nothing was enqueued** —
     * the structured "reconnect to save" the binding surface (UI / agent / OS intent) reports instead
     * of a swallowed exception or a false [Accepted].
     */
    data class Offline(override val kind: CommandKind) : CommandResult

    /**
     * An [CommandKind.onlineOnly] command reached the server but it rejected the request (e.g. a 4xx) —
     * a [message] the binding surface can show. Distinct from [Offline] because retrying offline won't
     * help; distinct from [Rejected] because it is a server verdict, not the pure pre-flight gate.
     */
    data class Failed(override val kind: CommandKind, val message: String) : CommandResult
}

/** Why a [CommandResult.Rejected] command was refused. */
enum class RejectionReason {
    /** The command does not apply to the target's current state ([CommandKind.enabledFor] returned false). */
    NotApplicable,
}
