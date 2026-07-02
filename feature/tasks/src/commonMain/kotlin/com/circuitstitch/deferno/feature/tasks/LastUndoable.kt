package com.circuitstitch.deferno.feature.tasks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The three relative move commands — typed so the View can localize the shake confirm
 * ("Undo [reorder]?"); [token] is the legacy lowercase label the SwiftUI bridges still render.
 */
enum class MoveOperation(val token: String) {
    Reorder("reorder"),
    Indent("indent"),
    Outdent("outdent"),
}

/**
 * One recorded inverse action (ADR-0034 decision 8, #230): the [operation] (typed, for the
 * shake confirm — "Undo [operation]?"), whether it was a [structural] move (reparent / indent / outdent →
 * the "Moved · Undo" snackbar; a plain same-level reorder → no snackbar, but still shake-undoable), and the
 * [action] that reverts it. [id] is a monotonic token so a fresh record re-fires the (single-shot) snackbar
 * effect even when two moves share an operation/structural pair.
 */
data class Undoable(
    val id: Int,
    val operation: MoveOperation,
    val structural: Boolean,
    val action: suspend () -> Unit,
)

/**
 * A **single-level** "last undoable action" register (ADR-0034 decision 8, #230): holds the inverse of the
 * most recent undoable command so one undo path — the snackbar action, the long-press menu, or a shake —
 * can replay it. Move-only in v1 (the hook is **shaped to grow** into a general last-action undo, a deferred
 * fast-follow). Single-level: [record] replaces any prior entry; [undo]/[clear] empty it.
 *
 * Pure and platform-neutral (no Decompose / coroutine scope), so the component owns the wiring and this stays
 * directly unit-testable (commonTest).
 */
class LastUndoable {
    private var counter = 0
    private val _current = MutableStateFlow<Undoable?>(null)

    /** The current recorded inverse, or `null` when nothing is undoable. The component derives its state from this. */
    val current: StateFlow<Undoable?> = _current.asStateFlow()

    /** Record the inverse of a just-applied command, replacing any prior one (single-level). */
    fun record(operation: MoveOperation, structural: Boolean, action: suspend () -> Unit) {
        _current.value = Undoable(++counter, operation, structural, action)
    }

    /** Drop the current entry without running it (e.g. when its target is no longer valid). */
    fun clear() {
        _current.value = null
    }

    /**
     * Run the recorded inverse (if any) and clear it, so the same action is never replayed twice. Returns the
     * [Undoable] that ran, or `null` when nothing was recorded.
     */
    suspend fun undo(): Undoable? {
        val entry = _current.value ?: return null
        _current.value = null
        entry.action()
        return entry
    }
}
