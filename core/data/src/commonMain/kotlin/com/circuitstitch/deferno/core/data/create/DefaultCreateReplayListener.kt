package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.outbox.CreateReplayListener
import com.circuitstitch.deferno.core.model.ItemKind

/**
 * The production [CreateReplayListener] (#185): resolves an offline create the instant its outbox entry
 * replays.
 *
 * - **Replayed.** When the server honored the client id (the normal path — equal, or a blank/unparsed
 *   id we treat as honored), the pending-create row is simply [PendingCreateStore.confirm]ed. When the
 *   server returned a **different** canonical id, [ItemIdHealer] re-points every local reference
 *   (Item row, parent/child, plan, queued outbox entries) and the pending-create row is re-keyed then
 *   confirmed. The heal flag is propagated up so the processor knows the queue changed.
 * - **Rejected.** Not handled here: the processor dead-letters a terminally-rejected create (the optimistic
 *   Item row + its pending-create purge-protection are preserved, never undone — the user's create must
 *   never silently vanish).
 */
class DefaultCreateReplayListener(
    private val healer: ItemIdHealer,
    private val pendingCreateStore: PendingCreateStore,
) : CreateReplayListener {

    override suspend fun onReplayed(clientId: String, kind: ItemKind, serverId: String): Boolean {
        if (serverId.isBlank() || serverId == clientId) {
            pendingCreateStore.confirm(clientId, clientId)
            return false
        }
        val healed = healer.heal(clientId, serverId, kind)
        pendingCreateStore.rekey(clientId, serverId)
        pendingCreateStore.confirm(serverId, serverId)
        return healed
    }
}
