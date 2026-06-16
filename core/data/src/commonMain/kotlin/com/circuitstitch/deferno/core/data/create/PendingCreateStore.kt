package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.model.ItemKind

/** Where an offline create is in its replay lifecycle (#185). */
enum class PendingCreateState { Pending, Confirmed }

/**
 * One row of the offline-create side table (#185): the client-generated [itemId], its converged
 * [itemKind], the replay [state], and the server's [canonicalId] once confirmed (equal to [itemId] on
 * the normal path; different only on the rare id-heal path).
 */
data class PendingCreate(
    val itemId: String,
    val itemKind: ItemKind,
    val state: PendingCreateState,
    val canonicalId: String?,
)

/**
 * The local source-of-truth port for offline-create state (#185). Keeps the "is this Item a
 * not-yet-replayed create?" fact in a sparse side table rather than on the domain Item models
 * (ADR-0001 keeps those a clean projection of server truth). The reconcile reads [pendingIds] to
 * protect offline-created rows from the orphan-purge until they replay; the outbox replay listener
 * [confirm]s / [reject]s / [rekey]s a row as the create resolves.
 *
 * Extracting it behind a port keeps the lifecycle unit-testable against an in-memory fake on the
 * ADR-0006 JVM-fast path; [SqlDelightPendingCreateStore] proves the real SQL path.
 */
interface PendingCreateStore {

    /** Records a fresh offline create (state = pending). Idempotent on [itemId]. */
    suspend fun add(itemId: String, itemKind: ItemKind)

    /** Marks the create [itemId] confirmed by its successful replay, recording the server's [canonicalId]. */
    suspend fun confirm(itemId: String, canonicalId: String)

    /** Re-keys a row from [fromId] to [toId] when the server assigned a different canonical id (heal). */
    suspend fun rekey(fromId: String, toId: String)

    /** Drops the row [itemId] whose create the server terminally rejected (the optimism is undone). */
    suspend fun reject(itemId: String)

    /** The ids of creates still awaiting replay — read by the reconcile to protect them from purge. */
    suspend fun pendingIds(): Set<String>

    /** The whole table (diagnostics / read model). */
    suspend fun all(): List<PendingCreate>
}
