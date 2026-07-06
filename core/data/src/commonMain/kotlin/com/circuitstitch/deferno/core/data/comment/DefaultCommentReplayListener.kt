package com.circuitstitch.deferno.core.data.comment

import com.circuitstitch.deferno.core.data.outbox.CommentReplayListener
import com.circuitstitch.deferno.core.data.outbox.OutboxStore

/**
 * The production [CommentReplayListener] (ADR-0043): resolves an offline comment create the instant its
 * outbox entry replays. Because the backend never honours the client comment id, the rekey is the
 * mainline — *every* replay diverges:
 *
 * - **Replayed.** [onReplayed] rekeys the optimistic `commentEntity` row [clientId] → [serverId]
 *   ([CommentLocalStore.rekey], an in-place `UPDATE`; the schema has no FK), then re-points any
 *   already-queued `comment:<clientId>` edit/delete to the server id — the narrow substring re-point of
 *   [com.circuitstitch.deferno.core.data.create.ItemIdHealer.healOutbox] scoped to the outbox. It returns
 *   `true` **iff** it re-pointed a queued entry, so the processor breaks its now-stale `pending()` pass
 *   (a lone create with no queued edit leaves the outbox unchanged, so the pass may continue).
 * - **Rejected.** [onRejected] undoes the optimism: the optimistic row is hard-removed (the create never
 *   landed, so the row shouldn't linger).
 */
class DefaultCommentReplayListener(
    private val localStore: CommentLocalStore,
    private val outbox: OutboxStore,
) : CommentReplayListener {

    override suspend fun onReplayed(taskId: String, clientId: String, serverId: String): Boolean {
        // Blank/echoed id ⇒ the server (somehow) honoured the client id — nothing to rekey or re-point.
        if (serverId.isBlank() || serverId == clientId) return false
        localStore.rekey(clientId, serverId)
        return repointQueued(clientId, serverId)
    }

    override suspend fun onRejected(taskId: String, clientId: String) {
        localStore.deleteById(clientId)
    }

    /**
     * Re-point every queued outbox entry mentioning [clientId] to [serverId] in place (preserving FIFO),
     * so a queued `comment:<clientId>` edit/delete lands on the real comment. A UUID substring replace is
     * collision-safe. Returns `true` if any entry changed.
     */
    private suspend fun repointQueued(clientId: String, serverId: String): Boolean {
        var healed = false
        for (entry in outbox.pending()) {
            val newTarget = entry.target.replace(clientId, serverId)
            val newPath = entry.request.path.map { it.replace(clientId, serverId) }
            val newBody = entry.request.body?.replace(clientId, serverId)
            if (newTarget != entry.target || newPath != entry.request.path || newBody != entry.request.body) {
                outbox.update(entry.seq, newTarget, entry.request.copy(path = newPath, body = newBody))
                healed = true
            }
        }
        return healed
    }
}
