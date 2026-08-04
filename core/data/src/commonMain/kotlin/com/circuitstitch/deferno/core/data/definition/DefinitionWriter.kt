package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import kotlin.time.Instant

/**
 * The recurring-**definition** write seam (#299, widened by #378) — the Habit/Chore/Event sibling of
 * [com.circuitstitch.deferno.core.data.task.TaskWriter]. A definition's lifecycle is the
 * [DefinitionState] "light switch" (Active / In review / Archived), not a Task [WorkingState], so this is
 * a separate, kind-aware seam: each call **applies optimistically** to the correct per-kind local cache
 * — so the Tasks Item tree updates the instant the user acts, online or off — and **enqueues** an
 * idempotent `PATCH {habits|chores|events}/{id}` mutation to the outbox for replay (ADR-0001). See
 * [OutboxDefinitionWriter] and [com.circuitstitch.deferno.core.data.outbox.DefinitionMutation].
 *
 * It addresses the **raw Item id string** (cross-kind, like the move seam), with [kind] selecting the
 * per-kind store + endpoint, because the tree row is the cross-kind Item projection — not a kind-typed id.
 *
 * **Three verbs, not twelve.** The Task seam has one method per editable field because the Task detail
 * screen edits every field. This one covers exactly the recurring surfaces that exist: the light switch
 * plus the two soft-planning fields (#375) the Item tree can already set on a Task. Everything else on
 * the recurring update payloads (title, description, labels, pinned, `complete_by`, the recurrence rule)
 * has no driving surface yet and is deliberately absent rather than speculatively mirrored.
 *
 * **Delete is not here.** A recurring delete is `DELETE items/{id}` — kind-neutral, resolved server-side,
 * and chain-wide — so it lives on [com.circuitstitch.deferno.core.data.item.ItemWriter], which already
 * holds all four per-kind stores. See [com.circuitstitch.deferno.core.data.outbox.DeleteItem] for why
 * the per-kind route would be the wrong one.
 */
interface DefinitionWriter {

    /** Set the recurring [id]'s [DefinitionState] (`PATCH {kind}/{id} {"status":…}`); [kind] picks the route. */
    suspend fun setDefinitionState(id: String, kind: ItemKind, target: DefinitionState)

    /**
     * Set (or clear, with `null`) the recurring [id]'s soft target date
     * (`PATCH {kind}/{id} {"target_date":…}`) — when the person *wants* it done by (#375).
     *
     * The optimistic apply reproduces the server's write-time clamp: a [targetDate] past the definition's
     * `complete_by` is stored at the deadline, not past it (see
     * [com.circuitstitch.deferno.core.data.outbox.clampTargetDate]).
     */
    suspend fun setTargetDate(id: String, kind: ItemKind, targetDate: Instant?)

    /**
     * Set the recurring [id]'s urgency bucket (`PATCH {kind}/{id} {"priority":…}`, #375). Not nullable by
     * contract — "no priority" is [Priority.Normal], a real value, so there is nothing to clear.
     */
    suspend fun setPriority(id: String, kind: ItemKind, priority: Priority)
}
