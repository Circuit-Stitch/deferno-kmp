package com.circuitstitch.deferno.core.data.connectivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [Connectivity] that mirrors a periodic [probe] into [online] — the best-effort posture for
 * platforms without a push-style reachability callback (the desktop, #158). Polling runs only while
 * [online] is subscribed (the outbox driver subscribes for the lifetime of an active session, so the
 * signal is live exactly when it matters and idle otherwise) and re-probes immediately on
 * resubscribe, so a fresh session never acts on a stale sample.
 *
 * A probe failure counts as **online** (fail open): this seam must never falsely report offline —
 * that would suppress flushes and block creates while the network is actually reachable — whereas a
 * false *online* just lets the request's own transport failure be the signal, as before #158.
 */
class PollingConnectivity(
    private val probe: suspend () -> Boolean,
    private val period: Duration = 15.seconds,
    scope: CoroutineScope,
) : Connectivity {
    override val online: StateFlow<Boolean> =
        flow {
            while (true) {
                emit(runCatching { probe() }.getOrDefault(true))
                delay(period)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(), initialValue = true)
}
