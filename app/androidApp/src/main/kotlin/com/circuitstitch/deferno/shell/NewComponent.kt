package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The **New** create-surface logic (#71, ADR-0015/0016): the explicit kind picker + per-kind form
 * state + the online-only create dispatch. It is deliberately Compose-free so the [NewScreen] View
 * stays a thin render of this state, and so the create flow is unit-testable without a UI.
 *
 * The picker is an **explicit** Task/Habit/Chore/Event segmented choice (ADR-0015 — never inferred
 * from field content); the form adapts to [selectedKind]. Submitting routes through the shell's
 * online-only [create] seam (the command executor's `CreateItem`), and the resulting [status] tells
 * the View whether to dismiss (created), show "reconnect to save" ([NewStatus.Offline]), or show a
 * gentle error ([NewStatus.Failed]) — nothing is enqueued offline (ADR-0016).
 */
interface NewComponent {
    val state: StateFlow<NewState>

    /** Pick the kind to create — the explicit segmented control (ADR-0015). */
    fun selectKind(kind: ItemKind)

    fun setTitle(title: String)
    fun setNotes(notes: String)

    /** Submit the per-kind form via the online-only create path. */
    fun submit()

    /** Dismiss the surface (the host clears the overlay). */
    fun dismiss()
}

/** The New surface's render state. */
data class NewState(
    val selectedKind: ItemKind = ItemKind.Task,
    val title: String = "",
    val notes: String = "",
    val status: NewStatus = NewStatus.Editing,
) {
    /** Create is enabled only with a non-blank title (the one universally-required field). */
    val canSubmit: Boolean get() = title.isNotBlank() && status != NewStatus.Submitting
}

/** Where the New surface is in its create lifecycle. */
sealed interface NewStatus {
    data object Editing : NewStatus
    data object Submitting : NewStatus

    /** Offline (ADR-0016): the gentle "reconnect to save"; nothing was enqueued. */
    data object Offline : NewStatus

    /** A server rejection — a gentle error [message]. */
    data class Failed(val message: String) : NewStatus
}

/**
 * Default [NewComponent]. [create] is the shell's online-only create seam; [onCreated] is invoked when
 * the server confirms the create (the host dismisses the overlay and the new row is already observable
 * via the repository `Flow`). [launch] runs the suspending create on the shell's scope.
 */
class DefaultNewComponent(
    private val create: suspend (CreateItem.Payload) -> CommandResult,
    private val onCreated: () -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
) : NewComponent {

    private val _state = MutableStateFlow(NewState())
    override val state: StateFlow<NewState> = _state

    override fun selectKind(kind: ItemKind) = _state.update { it.copy(selectedKind = kind, status = NewStatus.Editing) }
    override fun setTitle(title: String) = _state.update { it.copy(title = title, status = NewStatus.Editing) }
    override fun setNotes(notes: String) = _state.update { it.copy(notes = notes, status = NewStatus.Editing) }

    override fun submit() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        _state.update { it.copy(status = NewStatus.Submitting) }
        launch {
            when (create(snapshot.toPayload())) {
                is CommandResult.Accepted -> onCreated()
                is CommandResult.Offline -> _state.update { it.copy(status = NewStatus.Offline) }
                is CommandResult.Failed -> _state.update { it.copy(status = NewStatus.Failed("Could not save. Try again.")) }
                // The create gate never rejects pre-flight (CreateItem has no enabledFor rule), but be total.
                is CommandResult.Rejected -> _state.update { it.copy(status = NewStatus.Failed("Could not save.")) }
            }
        }
    }

    override fun dismiss() = onCreated()
}

/**
 * Build the online-only create payload for the selected kind (ADR-0016). Notes map to `description`;
 * the recurring kinds default to a daily recurrence in v1 (the recurrence picker is a follow-up), and
 * an Event needs a start, so a bare Event create falls back to a placeholder start the user can edit
 * after it joins the offline-first edit flow. The Chore group/rotation is deferred (ADR-0015).
 */
internal fun NewState.toPayload(): CreateItem.Payload {
    val notesOrNull = notes.ifBlank { null }
    return when (selectedKind) {
        ItemKind.Task -> CreateItem.Payload.Task(CreateTaskPayload(title = title.trim(), description = notesOrNull))
        ItemKind.Habit -> CreateItem.Payload.Habit(
            CreateHabitPayload(title = title.trim(), recurrence = RecurrenceDto(type = "daily"), description = notesOrNull),
        )
        ItemKind.Chore -> CreateItem.Payload.Chore(
            CreateChorePayload(title = title.trim(), recurrence = RecurrenceDto(type = "daily"), description = notesOrNull),
        )
        ItemKind.Event -> CreateItem.Payload.Event(
            CreateEventPayload(title = title.trim(), completeBy = "", description = notesOrNull),
        )
    }
}
