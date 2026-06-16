package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.RemoteSnapshot

/**
 * Scriptable [ItemSnapshotSource] for the [ItemSync] tests (#226). A test sets [snapshot] to the
 * `/items` cold snapshot the next reconcile sees, then asserts how each kind reconciles into its store.
 * [failNext] simulates the offline-first failure path (a pull that can't reach the server → Unavailable).
 */
class FakeItemSnapshotSource(
    var snapshot: ItemSnapshot = ItemSnapshot(),
    var failNext: Boolean = false,
) : ItemSnapshotSource {

    override suspend fun fetchAll(): RemoteSnapshot<ItemSnapshot> =
        if (failNext) RemoteSnapshot.Unavailable else RemoteSnapshot.Available(snapshot)
}
