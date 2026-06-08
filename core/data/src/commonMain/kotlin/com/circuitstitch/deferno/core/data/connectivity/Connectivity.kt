package com.circuitstitch.deferno.core.data.connectivity

/**
 * The connectivity seam the **online-only create gate** (ADR-0016, #71) checks before POSTing. Create
 * is the one write that is *not* offline-first: the server has no idempotency key in v0.1, so a queued
 * create replayed after a network blip would duplicate the item (ADR-0016). The create writer therefore
 * gates on [isOnline] and, when offline, refuses with a gentle "reconnect to save" — enqueuing nothing.
 *
 * Deliberately minimal: a single suspend probe, so a per-platform actual (Android `ConnectivityManager`,
 * iOS `NWPathMonitor`, a desktop reachability check) can implement it later and a test can fake offline
 * trivially. Edits stay fully offline-first and never consult this seam (ADR-0001).
 */
fun interface Connectivity {
    /** Whether the device currently has a usable network path. */
    suspend fun isOnline(): Boolean
}

/**
 * The v1 default [Connectivity]: **assume online** and let the create call's own transport failure be
 * the real signal. This keeps the online-only gate honest without a platform reachability dependency —
 * if the network is actually down the POST returns a transport [com.circuitstitch.deferno.core.network.ApiError.Transport],
 * which the create writer maps to the same "reconnect to save" outcome (ADR-0016). A platform-aware
 * actual that can answer *before* the call (so the UI never even tries) is a non-breaking follow-up.
 */
class AssumeOnlineConnectivity : Connectivity {
    override suspend fun isOnline(): Boolean = true
}
