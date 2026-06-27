package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind

/**
 * The recurring-**definition** write seam (#299) — the Habit/Chore/Event sibling of
 * [com.circuitstitch.deferno.core.data.task.TaskWriter]. A definition's lifecycle is the
 * [DefinitionState] "light switch" (Active / In review / Archived), not a Task [WorkingState], so this is
 * a separate, kind-aware seam: each call **applies optimistically** to the correct per-kind local cache
 * — so the Tasks Item tree updates the instant the user acts, online or off — and **enqueues** an
 * idempotent `PATCH {habits|chores|events}/{id}` mutation to the outbox for replay (ADR-0001). See
 * [OutboxDefinitionWriter] and [com.circuitstitch.deferno.core.data.outbox.SetDefinitionState].
 *
 * It addresses the **raw Item id string** (cross-kind, like the move seam), with [kind] selecting the
 * per-kind store + endpoint, because the tree row is the cross-kind Item projection — not a kind-typed id.
 */
interface DefinitionWriter {

    /** Set the recurring [id]'s [DefinitionState] (`PATCH {kind}/{id} {"status":…}`); [kind] picks the route. */
    suspend fun setDefinitionState(id: String, kind: ItemKind, target: DefinitionState)
}
