package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.data.account.AccountDataStore
import com.circuitstitch.deferno.core.data.account.AccountRegistry
import com.circuitstitch.deferno.core.data.account.InMemoryAccountRegistry
import com.circuitstitch.deferno.core.data.account.NoOpAccountDataStore
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.data.auth.LoopbackBrowserAuthenticator
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.connectivity.PollingConnectivity
import com.circuitstitch.deferno.core.data.connectivity.anyNetworkInterfaceUp
import com.circuitstitch.deferno.core.scopes.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Desktop (JVM) AppScope actuals (ADR-0014): an in-memory roster (a persistent desktop registry is a
 * follow-up) and the no-op data store (the JVM driver's plain DB file has no separate sidecar
 * lifecycle to wipe yet — ADR-0009 desktop posture).
 */
@ContributesTo(AppScope::class)
interface JvmDataBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun accountRegistry(): AccountRegistry = InMemoryAccountRegistry()

    @Provides
    @SingleIn(AppScope::class)
    fun accountDataStore(): AccountDataStore = NoOpAccountDataStore

    /** The system-browser OAuth leg (ADR-0026): a loopback listener captures the redirect on desktop. */
    @Provides
    @SingleIn(AppScope::class)
    fun browserAuthenticator(): BrowserAuthenticator = LoopbackBrowserAuthenticator()

    /** Tags a minted token to this device (ADR-0026). */
    @Provides
    @SingleIn(AppScope::class)
    fun deviceName(): DeviceName = DeviceName("Deferno Desktop — ${System.getProperty("os.name") ?: "Desktop"}")

    /**
     * The connectivity seam (#71/#158, ADR-0016): the JVM has no push-style reachability callback, so
     * the best-effort interface poll mirrors offline/online for the create gate + the outbox driver's
     * reconnect edge. The poll only runs while observed (an active session), so the process-lifetime
     * scope created here hosts no busy work outside one. AppScope — a process concern, not per-Account.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun connectivity(): Connectivity = PollingConnectivity(
        probe = { anyNetworkInterfaceUp() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}
