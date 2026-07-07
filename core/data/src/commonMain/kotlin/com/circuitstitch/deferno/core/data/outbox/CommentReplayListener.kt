package com.circuitstitch.deferno.core.data.outbox

/**
 * The account-scoped seam the [OutboxProcessor] notifies when an offline **comment create** entry
 * replays (ADR-0043, "Comment-create idempotency") — the comment twin of [CreateReplayListener], and a
 * **second** listener beside it on the processor. Distinct because a comment is not kind-typed (no
 * `ItemKind`) and its heal is a comment-only re-key, not the full item-graph heal.
 *
 * Because the backend never honours the client comment id, the rekey is the **mainline, not a rare
 * heal**: *every* create replay reassigns a fresh server id, so [onReplayed] rekeys the optimistic
 * `commentEntity` row and re-points any already-queued `comment:<clientId>` edit/delete — and returns
 * `true` so the processor breaks its now-stale `pending()` pass (load-bearing: without the break the
 * processor would replay the queued edit against the dead client id → `404` → success → the edit is
 * silently lost).
 */
interface CommentReplayListener {

    /**
     * The comment create on [taskId] under [clientId] replayed; the server assigned [serverId].
     * Rekeys the optimistic row and re-points queued edits/deletes to [serverId].
     *
     * @return `true` if a queued `comment:<clientId>` entry was re-pointed, so the processor stops the
     *   current pass (its in-flight `pending()` snapshot is now stale) and lets the next flush re-read.
     */
    suspend fun onReplayed(taskId: String, clientId: String, serverId: String): Boolean

    /** The comment create under [clientId] was terminally rejected / exhausted — undo the optimistic post. */
    suspend fun onRejected(taskId: String, clientId: String)

    companion object {
        /** A listener that does nothing — the default the engine's own tests construct with. */
        val NoOp: CommentReplayListener = object : CommentReplayListener {
            override suspend fun onReplayed(taskId: String, clientId: String, serverId: String): Boolean = false
            override suspend fun onRejected(taskId: String, clientId: String) {}
        }
    }
}
