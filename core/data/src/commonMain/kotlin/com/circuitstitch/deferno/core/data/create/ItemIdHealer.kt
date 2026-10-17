package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.item.CachedItem
import com.circuitstitch.deferno.core.data.item.ItemLocalStore
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.repointId
import com.circuitstitch.deferno.core.data.plan.PlanLocalStore
import com.circuitstitch.deferno.core.model.ItemKind

/**
 * Repairs every local reference to an offline-created Item's **client** id when the server assigns it a
 * **different** canonical id (#185). Client-supplied ids are the normal path — the backend honors them —
 * so this fires only on the rare divergence, but it must leave the cache fully consistent when it does.
 * The outbox replay listener drives it the instant a create replays, before the processor advances to any
 * queued edit against the same id.
 *
 * What it re-points, from client id to canonical id:
 *
 * - the item row itself, inserted under the canonical id and deleted under the client one;
 * - the tree edges on every other cached row — `parentId` and `childIds`;
 * - plan slots referencing the id ([PlanLocalStore.rekeyItem]);
 * - on-device attachment rows keyed by the id ([rekeyAttachments], gh#223), which are Task-only: a
 *   brain-dump recording attached at accept time is keyed by the create-time client id, so without this
 *   it is orphaned the moment the Task moves to the server id;
 * - any already-queued outbox entry whose `target`, `path` or `body` mentions the client id
 *   ([OutboxStore.update], in place so FIFO order is preserved). A UUID substring replace is
 *   collision-safe, since ids do not appear as substrings of unrelated content.
 *
 * **The tree sweep stopped being Task-only at #422.** It ran over the Task store alone while the cache
 * held four tables, because only that store could be walked in one pass. The forest has always nested a
 * child of any kind under a parent of any kind, so a recurring definition parented to an offline-created
 * Task kept a dead `parentId` through the heal. One cache means one sweep and the gap closes with it.
 *
 * The pending-create row's own re-key and confirm is owned by the listener, not here.
 */
class ItemIdHealer(
    private val items: ItemLocalStore,
    private val planStore: PlanLocalStore,
    private val outbox: OutboxStore,
    // gh#223: re-point on-device attachments (Task-only) from the client id to the canonical id. A
    // functional seam (LocalAttachmentRepository::rekeyTask in prod) — the data layer's existing idiom —
    // so the healer needs no new interface and tests pass a capturing lambda. No-op default keeps the
    // many existing constructions building.
    private val rekeyAttachments: suspend (from: String, to: String) -> Unit = { _, _ -> },
) {

    /**
     * Re-points all local references for the item from [clientId] to [canonicalId]. A no-op returning
     * `false` when the ids are equal, which is the normal path; otherwise performs the heal and returns
     * `true`, which the processor uses to know the outbox queue may have changed.
     *
     * [kind] selects the attachment sweep and nothing else. The row itself is found by id.
     */
    suspend fun heal(clientId: String, canonicalId: String, kind: ItemKind): Boolean {
        if (clientId == canonicalId) return false

        items.transaction { store ->
            // Snapshot the ids before mutating — the upsert and delete below change the row set, and a
            // store whose allIds() is a live view would otherwise fault mid-iteration.
            for (id in store.allIds().toList()) {
                val row = store.get(id) ?: continue
                val healed = row.repointing(clientId, canonicalId) ?: continue
                store.upsert(healed)
                // The created row moved to a new primary key, so the old one has to go. Ordered after
                // the insert: the two ids differ, so nothing races, and the row is never absent.
                if (row.id == clientId) store.delete(clientId)
            }
        }

        if (kind == ItemKind.Task) rekeyAttachments(clientId, canonicalId)
        // Kind-neutral since #385: the daily plan holds items of any kind, so an offline-created
        // definition planned before its id was healed leaves a plan slot pointing at the dead client id.
        planStore.rekeyItem(clientId, canonicalId)
        outbox.repointId(clientId, canonicalId)
        return true
    }

    /**
     * This row with every reference to [clientId] moved to [canonicalId], or `null` when it holds none.
     *
     * Identity and tree position are all [com.circuitstitch.deferno.core.model.plugin.Core], so the heal
     * touches no plugin at all. That is the shape ADR-0055 predicts: an id is not a Family.
     */
    private fun CachedItem.repointing(clientId: String, canonicalId: String): CachedItem? {
        val core = item.core
        val parentId = if (core.parentId == clientId) canonicalId else core.parentId
        val childIds = core.childIds.map { if (it == clientId) canonicalId else it }
        val isCreatedRow = core.id == clientId
        if (!isCreatedRow && parentId == core.parentId && childIds == core.childIds) return null
        return copy(
            item = item.copy(
                core = core.copy(
                    id = if (isCreatedRow) canonicalId else core.id,
                    parentId = parentId,
                    childIds = childIds,
                ),
            ),
        )
    }
}
