package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind

/**
 * The narrow write seam the Tasks Item tree drives to set a recurring **definition's** [DefinitionState]
 * (#299) — the Habit/Chore/Event "light switch" sibling of [WorkingStateEditor] (which is Task-only). It
 * is deliberately smaller than the whole `CommandExecutor`: the feature layer needs exactly one verb —
 * "set this definition to that state" — so it depends on this interface, not the command registry directly,
 * keeping the component testable on a recording fake (the shell supplies the real implementation over
 * `CommandExecutor` + `SetDefinitionState`, ADR-0007).
 *
 * **Offline-first (ADR-0001).** The implementation dispatches `SetDefinitionState` through the executor:
 * optimistic per-kind local apply + outbox enqueue. The tree row is the cross-kind Item projection, so the
 * component resolves the row's [ItemKind] from its current state and passes it here — the writer needs it
 * to route to the right per-kind store + `{habits|chores|events}/{id}` endpoint.
 */
fun interface DefinitionStateEditor {

    /** Set the recurring [id] (of [kind]) to [target] — optimistic apply + outbox enqueue. */
    suspend fun setDefinitionState(id: String, kind: ItemKind, target: DefinitionState)

    companion object {
        /** The default no-op editor, so the tree's read/move-only tests construct the component without it. */
        val NONE: DefinitionStateEditor = DefinitionStateEditor { _, _, _ -> }
    }
}
