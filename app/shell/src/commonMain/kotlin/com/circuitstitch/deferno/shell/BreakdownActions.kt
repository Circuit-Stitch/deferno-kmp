package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.feature.tasks.WorkingStateEditor

/**
 * The structural-move seam the iOS-native **Breakdown** state machine drives (Deferno#525). Breakdown's
 * orchestration is native Swift (idiomatic Apple: a deterministic state machine + a Foundation Models
 * answer-classifier), but the *moves it applies* must stay on the shared offline-first write path so they
 * carry the same client-UUID idempotency and outbox replay as every other edit (#185, ADR-0001/0007). So
 * the Swift engine never touches the command registry: it calls these String-typed methods, and
 * [CommandBreakdownActions] maps them onto the existing [com.circuitstitch.deferno.core.domain.command.CommandExecutor]
 * seams behind [AccountSession].
 *
 * Plain `String`/`String?` operands deliberately — value classes ([TaskId]) and the sealed
 * [CreateItem.Payload] are header-erased across the SKIE bridge, so the boundary speaks ids and ISO dates
 * and the conversion happens here.
 *
 * v1 covers only the moves with a client write path today (capture-as-child, drop, add-to-plan). The
 * blocked moves — `set_blocked_by`, Motivation, lower-priority — are net-new backend modules (Deferno#525
 * / #526) and land here as new methods when their Commands exist; until then the Swift classifier degrades
 * those impediment classes to reflect-and-note.
 */
interface BreakdownActions {
    /**
     * Capture a child Task titled [title] under [parentId] — the "too big → smaller parts" and
     * "spin off a prerequisite" moves. A child Task *is* the subtask: create-with-`parent_id` reparents
     * atomically, so no separate move_item is needed (ADR-0034). Returns the new item's client id
     * (offline-first — always queued), or `null` if [title] is blank (nothing is captured).
     */
    suspend fun captureSubtask(parentId: String, title: String): String?

    /** Drop [taskId] (`→ WorkingState.Dropped`) — the recoverable "let it go" terminal, never a hard delete. */
    suspend fun drop(taskId: String)

    /** Add [taskId] to **today's** plan — the "it's Ready, put it on the plan" terminal move. */
    suspend fun addToPlan(taskId: String)
}

/**
 * [BreakdownActions] over [AccountSession]'s existing offline-first seams — passed as the three narrow
 * functions it needs (not the whole session) so it's trivially testable with spies and mirrors the
 * `command*Editor(executor)` factory style in [AccountSession].
 */
class CommandBreakdownActions(
    private val createItem: suspend (CreateItem.Payload) -> CommandResult,
    private val workingStateEditor: WorkingStateEditor,
    private val addToPlanFn: suspend (TaskId) -> Unit,
) : BreakdownActions {

    override suspend fun captureSubtask(parentId: String, title: String): String? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        val result = createItem(
            CreateItem.Payload.Task(CreateTaskPayload(title = trimmed, parentId = parentId)),
        )
        return (result as? CommandResult.Accepted)?.itemId
    }

    override suspend fun drop(taskId: String) {
        // No `current` row — the DropTask command's own gate handles the already-Dropped case, matching
        // how AccountSession.deleteTask dispatches without a pre-flight row.
        workingStateEditor.setWorkingState(TaskId(taskId), WorkingState.Dropped, current = null)
    }

    override suspend fun addToPlan(taskId: String) {
        addToPlanFn(TaskId(taskId))
    }
}
