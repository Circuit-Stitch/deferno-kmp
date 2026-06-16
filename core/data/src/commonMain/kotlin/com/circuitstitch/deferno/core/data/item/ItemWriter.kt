package com.circuitstitch.deferno.core.data.item

/**
 * The cross-kind Item **write** seam the Tasks tree drives for the modal Move (ADR-0034 decision 5,
 * #228) — the reparent/reorder half `core/domain/.../command/Command.kt` deferred until a write seam
 * existed. The read half is [ItemRepository]; this is its write sibling, deliberately the **one** verb
 * the tree needs (relative ↑↓ reorder + ‹› indent/outdent all reduce to a `move`), kept separate from
 * the per-kind writers because a move spans all four kinds at once (a Habit may sit under a Task).
 *
 * **Offline-first (ADR-0001).** [move] applies optimistically across the four per-kind stores — so the
 * tree re-flattens the instant the user presses — and enqueues a `Move` outbox mutation
 * (`POST items/{id}/move`) for replay. A server **400** (cycle) is a terminal rejection the next
 * cold-snapshot reconcile corrects (LWW); the UI greys out illegal targets, so it is only a rare race.
 */
interface ItemWriter {

    /**
     * Move [id] under [newParentId] (`null` = detach to root) to insertion index [position] among the
     * destination parent's children. Optimistic local reorder + outbox enqueue. A move of an uncached id
     * still enqueues (the write isn't lost) but skips the local apply — the reconcile after replay
     * materialises server truth.
     */
    suspend fun move(id: String, newParentId: String?, position: Int)
}
