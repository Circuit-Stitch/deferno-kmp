package com.circuitstitch.deferno.core.data.comment

import com.circuitstitch.deferno.core.data.outbox.CommentReplayListener
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.repointId

/**
 * The production [CommentReplayListener] (ADR-0043): resolves an offline comment create the instant its
 * outbox entry replays. Because the backend never honours the client comment id, the rekey is the
 * mainline — *every* replay diverges:
 *
 * - **Replayed.** [onReplayed] rekeys the optimistic `commentEntity` row [clientId] → [serverId]
 *   ([CommentLocalStore.rekey], an in-place `UPDATE`; the schema has no FK), then re-points any
 *   already-queued `comment:<clientId>` edit/delete to the server id via the shared
 *   [com.circuitstitch.deferno.core.data.outbox.repointId] outbox sweep (the same helper the #185
 *   [com.circuitstitch.deferno.core.data.create.ItemIdHealer] uses). It returns `true` **iff** it
 *   re-pointed a queued entry, so the processor breaks its now-stale `pending()` pass (a lone create
 *   with no queued edit leaves the outbox unchanged, so the pass may continue).
 * - **Rejected.** Not handled here: the processor dead-letters a terminally-rejected create (the
 *   optimistic row is preserved, never undone — the user's comment must not silently vanish).
 */
class DefaultCommentReplayListener(
    private val localStore: CommentLocalStore,
    private val outbox: OutboxStore,
) : CommentReplayListener {

    override suspend fun onReplayed(taskId: String, clientId: String, serverId: String): Boolean {
        // Blank/echoed id ⇒ the server (somehow) honoured the client id — nothing to rekey or re-point.
        if (serverId.isBlank() || serverId == clientId) return false
        localStore.rekey(clientId, serverId)
        // Re-point any queued `comment:<clientId>` edit/delete so it lands on the real comment; the
        // boolean tells the processor whether the queue moved (a lone create leaves it unchanged).
        return outbox.repointId(clientId, serverId)
    }
}
