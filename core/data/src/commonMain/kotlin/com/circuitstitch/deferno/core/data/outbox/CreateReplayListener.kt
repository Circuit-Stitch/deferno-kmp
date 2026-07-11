package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.ItemKind

/**
 * The account-scoped seam the [OutboxProcessor] notifies when an offline **create** entry replays
 * (#185). Layering: the app-scoped [OutboxRequestSender] only speaks HTTP (it returns the server id);
 * resolving that into account-scoped local effects — confirming the pending-create row and **healing** a
 * divergent canonical id (re-keying the Item row, parent/child refs, plan rows, and queued outbox
 * entries) — lives here, behind a port so the processor's FIFO/backoff engine stays unit-testable with a
 * [NoOp]. A terminal rejection is not a local effect here: the processor dead-letters it (the optimistic
 * insert is preserved, never undone).
 */
interface CreateReplayListener {

    /**
     * The create of [kind] under [clientId] replayed successfully; the server's assigned id is
     * [serverId] (blank when the response carried none — treat as the client id). Confirms the
     * pending-create row, and when [serverId] diverges from [clientId], heals all references.
     *
     * @return `true` if a heal mutated the outbox queue, so the processor should stop the current pass
     *   (its in-flight `syncable()` snapshot is now stale) and let the next flush re-read.
     */
    suspend fun onReplayed(clientId: String, kind: ItemKind, serverId: String): Boolean

    // No onRejected: a terminally-rejected create is dead-lettered by the processor, NOT undone — the
    // optimistic Item row + its pending-create protection are preserved (the user's create must never
    // silently vanish). See OutboxProcessor.

    companion object {
        /** A listener that does nothing — the default the engine's own tests construct with. */
        val NoOp: CreateReplayListener = object : CreateReplayListener {
            override suspend fun onReplayed(clientId: String, kind: ItemKind, serverId: String): Boolean = false
        }
    }
}
