package com.circuitstitch.deferno.core.data.item

/**
 * The cross-kind Item **write** seam the Tasks tree drives (ADR-0049 decision 5, #228; #389) — the
 * reparent/reorder half `core/domain/.../command/Command.kt` deferred until a write seam existed. The
 * read half is [ItemRepository]; this is its write sibling, holding the verbs that are **kind-neutral by
 * nature** and so belong to no per-kind writer: a [move] spans all four kinds at once (a Habit may sit
 * under a Task), and a [delete] is one route the server resolves the kind for itself.
 *
 * **Offline-first (ADR-0001).** Both verbs apply optimistically across the four per-kind stores — so the
 * tree re-flattens the instant the user presses — and enqueue an outbox mutation for replay.
 */
interface ItemWriter {

    /**
     * Move [id] under [newParentId] (`null` = detach to root) to insertion index [position] among the
     * destination parent's children. Optimistic local reorder + outbox enqueue. A move of an uncached id
     * still enqueues (the write isn't lost) but skips the local apply — the reconcile after replay
     * materialises server truth. A server **400** (cycle) is a terminal rejection the next cold-snapshot
     * reconcile corrects (LWW); the UI greys out illegal targets, so it is only ever a rare race.
     */
    suspend fun move(id: String, newParentId: String?, position: Int)

    /**
     * Soft-delete [id], whatever kind it is (`DELETE items/{id}`, #389). Optimistic local **tombstone**
     * (the row drops out of every `observeActive`) + outbox enqueue; deleting an uncached id still
     * enqueues.
     *
     * **No [ItemKind] operand, deliberately.** The server route resolves the kind itself and, for a
     * recurring kind, deletes the whole Series chain — which is the semantic the tree means by "delete
     * this item". The per-kind `DELETE /{kind}/{id}` archives a single Segment and leaves its siblings
     * alive; see [com.circuitstitch.deferno.core.data.outbox.DeleteItem].
     *
     * It is a **soft** delete: the occurrence records and the recurrence series survive it. Anything
     * that words a confirmation for this call has to say so.
     */
    suspend fun delete(id: String)
}
