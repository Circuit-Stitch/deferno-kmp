package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Drives an Active Account's offline outbox (#143/#158). On [drive] it flushes the queued writes (when
 * online) **before** the settings reconcile — sequenced so the settings pull can't fetch a snapshot that
 * predates the just-flushed writes (the #143 cold-start theme revert) — then re-runs that pair on the
 * offline→online edge and re-flushes every [flushPeriod] while online. [drive] cancels any prior
 * session's loop first, so the driver is bound to exactly the Active Account (account isolation,
 * ADR-0002/0014); [stop] tears it down on sign-out so a signed-out Account's outbox is never flushed again.
 * Both cancel cooperatively (no join), so an in-flight flush from the prior session may still complete —
 * matching the pre-extraction `RootComponent.driveOutboxFor` behaviour.
 *
 * The [connectivity] signal shapes both legs (#158): a periodic pass is skipped while known-offline — so a
 * long offline stretch can't walk a queued write into the replay engine's give-up policy (`maxAttempts`
 * measures real server failures, not flight mode) — and the offline→online edge triggers an immediate
 * flush-then-reconcile instead of waiting out the tick.
 *
 * It owns a single parent [Job] on [scope] (the component's lifecycle scope), so the reconnect-edge
 * collector is a child and a [stop] / re-[drive] cancels the whole loop. Extracted from `RootComponent`
 * so this flush timing has one home and a focused test (a virtual clock + a fake [Connectivity]).
 */
class OutboxDriver(
    private val scope: CoroutineScope,
    private val connectivity: Connectivity,
    private val now: () -> Instant,
    private val flushPeriod: Duration,
    /**
     * How often to reconcile the Activity ledger against the server (#364) while online. Deliberately far
     * slower than [flushPeriod]: the flush drains the user's OWN pending writes (latency the user feels),
     * whereas this pulls in what happened on other surfaces (latency nobody is waiting on). Activation and
     * the reconnect edge sync immediately regardless, so this tick only covers a long foreground idle.
     */
    private val activitySyncPeriod: Duration = DEFAULT_ACTIVITY_SYNC_PERIOD,
    /**
     * The context the flush loop runs on. The flush + settings reconcile do **synchronous** SQLite I/O
     * (`SqlDelightOutboxStore` runs its queries straight through on the calling dispatcher), so this must
     * be a background context — [scope] is the component's *Main* lifecycle scope, and running the flush
     * there blocks the UI thread on every activation, every [flushPeriod] tick, and every reconnect edge
     * (the 1-2s tap lag right after start / after idle). Defaulted to inherit [scope]'s dispatcher so the
     * virtual-clock tests keep their single-threaded scheduler; production passes `Dispatchers.IO`.
     */
    private val flushContext: CoroutineContext = EmptyCoroutineContext,
) {
    private var job: Job? = null

    /** Re-point the driver at [session] (cancelling any prior session's loop first). */
    fun drive(session: AccountSession) {
        job?.cancel()
        job = scope.launch(flushContext) {
            val online = connectivity.online
            if (online.value) guarded { flushToQuiescence(session) }
            guarded { session.settingsRepository.refresh() }
            guarded { session.syncActivity() }
            launch {
                // The reconnect edge: `online` is distinct-until-changed, so after dropping the current
                // value every `true` is an offline→online transition.
                online.drop(1).filter { it }.collect {
                    guarded { flushToQuiescence(session) }
                    guarded { session.settingsRepository.refresh() }
                    guarded { session.syncActivity() }
                }
            }
            launch {
                // The activity reconcile runs on its OWN, slower cadence rather than riding the flush
                // tick: it is a paged network read on a feed the user is usually not looking at, and
                // folding it into a 30s loop would multiply the app's steady-state request count for no
                // freshness the user perceives. The legs that matter for correctness — activation and the
                // reconnect edge — fire it immediately above.
                while (true) {
                    delay(activitySyncPeriod)
                    if (online.value) guarded { session.syncActivity() }
                }
            }
            while (true) {
                delay(flushPeriod)
                if (online.value) guarded { flushToQuiescence(session) }
            }
        }
    }

    /**
     * Flush the outbox repeatedly until a pass makes no progress or drains the queue (ADR-0043): an
     * offline comment-create heal breaks each pass on replay (the rekey stales the engine's `syncable()`
     * snapshot), so without this loop a burst of offline comments would drain one-per-tick. `succeeded > 0`
     * means the last pass advanced; `remaining > 0` means there is still work — loop only while both hold.
     */
    private suspend fun flushToQuiescence(session: AccountSession) {
        do {
            val result = session.flushOutbox(now())
        } while (result.succeeded > 0 && result.remaining > 0)
    }

    /** Stop driving (sign-out / no Active Account): the prior Account's outbox is never flushed again. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Run one driver step, swallowing any failure so a flush/reconcile throw can never crash the app —
     * the periodic loop just retries on the next tick (and the reconnect edge on the next transition).
     * On Kotlin/Native an uncaught exception in a [scope] coroutine (the loop is a child of the Main
     * lifecycle [scope], even though it runs on [flushContext]) aborts the process, so this guard is what
     * keeps a bad DB open, a network blip, or a schema downgrade from taking the whole UI down. [CancellationException] is rethrown so [stop] / re-[drive] / scene-destroy
     * still tears the driver down cleanly.
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // ponytail: swallow — no structured logging yet; the loop retries next tick. Log here once it lands.
        }
    }

    companion object {
        /** The default Activity-ledger reconcile cadence — see [activitySyncPeriod]. */
        val DEFAULT_ACTIVITY_SYNC_PERIOD: Duration = 5.minutes
    }
}
