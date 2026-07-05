package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.TaskId

/**
 * The dependency-edge write seam (#291): replace a Task's ordered `blockedBy` list (an empty list
 * clears it — the field is always present, never absent, ADR-0011).
 *
 * **Online-only, unlike [TaskWriter]** (the ConvertItem posture, ADR-0016): the server is the sole
 * validator of the edge set — a cycle or a cross-org edge is a `400` verdict the user must see *now*,
 * not a silently-dropped outbox replay. So the write applies optimistically, PATCHes immediately, and
 * **reverts the optimistic apply** on a server rejection, returning the structured outcome the binding
 * surface reports. Offline → [BlockedByResult.Offline], nothing applied or enqueued.
 */
interface BlockedByWriter {

    /** Replace [id]'s ordered blocker list with [blockers] (`PATCH tasks/{id} {"blocked_by":[…]}`). */
    suspend fun setBlockedBy(id: TaskId, blockers: List<BlockedByRef>): BlockedByResult
}

/** The disjoint outcomes of a [BlockedByWriter.setBlockedBy] — mirrors `CreateResult`'s convert arm. */
sealed interface BlockedByResult {

    /** Server-confirmed; the optimistic local row stands (the next `/items` reconcile refreshes flags). */
    data object Applied : BlockedByResult

    /** No connectivity: the PATCH was not attempted and nothing was applied — "reconnect to save". */
    data object Offline : BlockedByResult

    /** The server rejected the edge set (a 400 cycle / cross-org); the optimistic apply was reverted. */
    data class Failed(val message: String) : BlockedByResult
}
