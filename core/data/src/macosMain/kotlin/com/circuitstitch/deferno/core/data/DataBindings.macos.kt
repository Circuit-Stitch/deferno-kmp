package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.data.account.AccountDataStore
import com.circuitstitch.deferno.core.data.account.AccountRegistry
import com.circuitstitch.deferno.core.data.account.FileAccountRegistry
import com.circuitstitch.deferno.core.data.account.NoOpAccountDataStore
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.data.auth.MacBrowserAuthenticator
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.connectivity.PathMonitorConnectivity
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import platform.Foundation.NSHost
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * macOS AppScope actuals (ADR-0014 / ADR-0029), the twin of [IosDataBindings]. The roster
 * ([FileAccountRegistry]) and connectivity ([PathMonitorConnectivity]) are the same cross-Apple
 * `appleMain` implementations iOS uses; the two that genuinely differ are bound here:
 *  - [DeviceName] is the real host name (`NSHost.currentHost.localizedName`) — macOS has no `UIDevice`;
 *  - [BrowserAuthenticator] is [MacBrowserAuthenticator] (#189), the desktop loopback flow (the twin of
 *    the JVM `LoopbackBrowserAuthenticator`): it opens the user's real default browser and captures the
 *    redirect on a `127.0.0.1` listener — NOT a custom scheme (which LaunchServices second-instances on
 *    macOS) and NOT iOS's `ASWebAuthenticationSession`. See the ADR-0026 macOS amendment.
 *
 * macOS per-Account isolation rides the per-Account encrypted DB file + Keychain key (the shared
 * `appleMain` SQLDelight driver), like iOS — no separate sidecar wipe.
 */
@ContributesTo(AppScope::class)
interface MacosDataBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun accountRegistry(): AccountRegistry = FileAccountRegistry()

    @Provides
    @SingleIn(AppScope::class)
    fun accountDataStore(): AccountDataStore = NoOpAccountDataStore

    @Provides
    @SingleIn(AppScope::class)
    fun browserAuthenticator(): BrowserAuthenticator = MacBrowserAuthenticator()

    /** The Mac's user-visible host name (e.g. "Kyle's MacBook Pro"); a static label if unavailable. */
    @Provides
    @SingleIn(AppScope::class)
    fun deviceName(): DeviceName =
        DeviceName(NSHost.currentHost().localizedName?.takeIf { it.isNotBlank() } ?: "Deferno macOS")

    @Provides
    @SingleIn(AppScope::class)
    fun connectivity(): Connectivity = PathMonitorConnectivity()
}
