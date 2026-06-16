package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload

/**
 * The create + convert write seam (#185, ADR-0001 forward path from ADR-0016).
 *
 * **Create is offline-first.** Each `create*` mints a client-side Item UUID, inserts the optimistic
 * local row, records a pending create, and enqueues a `POST /{kind}` carrying that id on the outbox —
 * the backend dedupes the create on the client id (Kyle-Falconer/Deferno#402). So a create always
 * succeeds locally and returns [CreateResult.Created] with the client id; replay/confirm/heal happen on
 * the outbox. See [OfflineCreateWriter].
 *
 * **Convert is still online-only.** Converting an existing item's kind has no client-id idempotency
 * story, so [convert] keeps the ADR-0016 gate: online → POST + reconcile the cache; offline →
 * [CreateResult.Offline]; a 4xx → [CreateResult.Failed].
 */
interface CreateWriter {
    suspend fun createTask(payload: CreateTaskPayload): CreateResult
    suspend fun createHabit(payload: CreateHabitPayload): CreateResult
    suspend fun createChore(payload: CreateChorePayload): CreateResult
    suspend fun createEvent(payload: CreateEventPayload): CreateResult

    /** Convert the item [id] (currently [fromKind]) per [payload]; reconciles the local cache. */
    suspend fun convert(id: String, fromKind: ItemKind, payload: ConvertItemPayload): CreateResult
}

/**
 * The outcome of a create/convert. The disjoint cases the binding surface (UI, agent, OS intent) must
 * distinguish so an outcome is *structured*, not a swallowed exception:
 *
 * - [Created] — the local row exists (kind + id). For an offline-first create this is **always** the
 *   result (optimistically applied + enqueued — *not* server-confirmed, ADR-0001); for a convert it
 *   means the server confirmed and the cache was reconciled.
 * - [Offline] — a **convert** refused before/at the POST because there is no connectivity; nothing was
 *   enqueued ("reconnect to save"). Create never returns this any more.
 * - [Failed] — a **convert** reached the server but it rejected (4xx) or another error occurred; a
 *   gentle error. Distinct from [Offline] because retrying offline won't help.
 */
sealed interface CreateResult {
    data class Created(val kind: ItemKind, val id: String) : CreateResult
    data object Offline : CreateResult
    data class Failed(val message: String) : CreateResult
}
