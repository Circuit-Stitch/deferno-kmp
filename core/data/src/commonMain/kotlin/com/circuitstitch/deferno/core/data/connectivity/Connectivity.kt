package com.circuitstitch.deferno.core.data.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The AppScope connectivity seam, with two consumers:
 *
 * - The **online-only convert gate** (ADR-0016) probes [isOnline] before POSTing. Convert is the write
 *   that is *not* offline-first: it mutates an existing item's kind with no client-id idempotency story,
 *   so when offline the create writer refuses with a gentle "reconnect to save" — enqueuing nothing.
 *   (Create itself became offline-first once the backend honored client-supplied ids — #185 /
 *   Kyle-Falconer/Deferno#402 — and no longer gates on this seam.)
 * - The **outbox driver** (#158) observes [online]: the offline→online edge triggers an immediate
 *   flush of the queued offline writes (instead of waiting out the periodic tick), and a flush pass
 *   is skipped while known-offline so a long offline stretch can't walk a queued write into the
 *   replay engine's give-up policy (`maxAttempts` measures *real* server failures, not flight mode).
 *
 * Per-platform impls (Android `ConnectivityManager`, iOS `NWPathMonitor`, the desktop interface
 * poll) mirror the OS signal into [online]; [AssumeOnlineConnectivity] is the non-breaking fallback.
 * Edits stay fully offline-first and never gate on this seam (ADR-0001) — it only *accelerates*
 * their replay.
 */
interface Connectivity {
    /**
     * Whether the device currently has a usable network path — hot and distinct-until-changed (a
     * `true` after a `false` IS the reconnect edge). False positives are fine (the request's own
     * transport failure is the real signal, backed by the outbox's retry/backoff); a false *negative*
     * is what impls must avoid, since it would suppress flushes while reachable.
     */
    val online: StateFlow<Boolean>

    /** Snapshot probe for the convert gate (ADR-0016): the current [online] value by default. */
    suspend fun isOnline(): Boolean = online.value
}

/**
 * The fallback [Connectivity]: **assume online** and let each call's own transport failure be the
 * real signal — exactly the pre-#158 behaviour. With it the outbox driver's edge never fires and no
 * flush pass is ever skipped (the periodic tick + per-entry backoff govern alone), and the convert
 * gate stays honest: a POST while actually offline returns a transport
 * [com.circuitstitch.deferno.core.network.ApiError.Transport], which the create writer maps to the
 * same "reconnect to save" outcome (ADR-0016).
 */
class AssumeOnlineConnectivity : Connectivity {
    override val online: StateFlow<Boolean> = MutableStateFlow(true)
}
