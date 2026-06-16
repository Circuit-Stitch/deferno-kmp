package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.RemoteSnapshot

/**
 * The network port the `/items` cold snapshot is pulled through (ADR-0034, #226) — the item-wide
 * successor to the legacy task-only `TaskRemoteSource.fetchAll`. It speaks the *domain* (the wire
 * polymorphic `ItemView` is condensed at the network edge by the #18 mappers), so [ItemSync] never
 * touches a DTO.
 *
 * Offline-first (ADR-0001): a failed pull reports [RemoteSnapshot.Unavailable] (not a throw), distinct
 * from an [RemoteSnapshot.Available] *empty* snapshot — so the reconcile purges a genuinely-emptied
 * server but leaves the cache intact when offline.
 */
interface ItemSnapshotSource {

    /**
     * The full `/items` snapshot as [RemoteSnapshot.Available] (possibly empty), partitioned by kind;
     * [RemoteSnapshot.Unavailable] on failure.
     */
    suspend fun fetchAll(): RemoteSnapshot<ItemSnapshot>
}
