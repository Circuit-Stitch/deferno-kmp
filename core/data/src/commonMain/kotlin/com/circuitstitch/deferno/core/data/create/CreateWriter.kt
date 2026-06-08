package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload

/**
 * The **online-only** create + convert write seam (ADR-0016, #71). It is deliberately **not** the
 * offline-first outbox path: a create has no server idempotency key in v0.1, so it is never enqueued —
 * the writer gates on connectivity, POSTs directly, and on success seeds the server-assigned id into
 * the matching local store so the row joins the normal offline-first observe/edit flow. Offline, it
 * refuses with [CreateResult.Offline] (the gentle "reconnect to save"), **enqueuing nothing**.
 *
 * Convert (the post-creation counterpart) is online-only for the same reason and reconciles the cache:
 * the converted item changes kind, so the old-kind row is removed and the new-kind row seeded.
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
 * The honest outcome of an online-only create/convert (ADR-0016). Three disjoint cases the binding
 * surface (UI, agent, OS intent) must distinguish so the connectivity requirement is *structured*,
 * not a swallowed exception:
 *
 * - [Created] — the server confirmed the create/convert and the local row is seeded (kind + id).
 * - [Offline] — refused before/at the POST because there is no connectivity; **nothing was enqueued**.
 *   The create surface shows "reconnect to save".
 * - [Failed] — the server reached but rejected (4xx) or another error occurred; the create surface
 *   shows a gentle error. Distinct from [Offline] because retrying offline won't help.
 */
sealed interface CreateResult {
    data class Created(val kind: ItemKind, val id: String) : CreateResult
    data object Offline : CreateResult
    data class Failed(val message: String) : CreateResult
}
