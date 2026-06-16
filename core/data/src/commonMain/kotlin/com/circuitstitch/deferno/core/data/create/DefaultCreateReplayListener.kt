package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.CreateReplayListener
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId

/**
 * The production [CreateReplayListener] (#185): resolves an offline create the instant its outbox entry
 * replays.
 *
 * - **Replayed.** When the server honored the client id (the normal path — equal, or a blank/unparsed
 *   id we treat as honored), the pending-create row is simply [PendingCreateStore.confirm]ed. When the
 *   server returned a **different** canonical id, [ItemIdHealer] re-points every local reference
 *   (Item row, parent/child, plan, queued outbox entries) and the pending-create row is re-keyed then
 *   confirmed. The heal flag is propagated up so the processor knows the queue changed.
 * - **Rejected.** A terminal rejection / exhaustion undoes the optimism: the pending-create row is
 *   dropped and the optimistic local Item row deleted (the create never landed, so the row shouldn't
 *   linger — and, no longer pending, it is no longer purge-protected).
 */
class DefaultCreateReplayListener(
    private val healer: ItemIdHealer,
    private val pendingCreateStore: PendingCreateStore,
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
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

    override suspend fun onRejected(clientId: String, kind: ItemKind) {
        pendingCreateStore.reject(clientId)
        when (kind) {
            ItemKind.Task -> taskStore.delete(TaskId(clientId))
            ItemKind.Habit -> habitStore.delete(HabitId(clientId))
            ItemKind.Chore -> choreStore.delete(ChoreId(clientId))
            ItemKind.Event -> eventStore.delete(EventId(clientId))
        }
    }
}
