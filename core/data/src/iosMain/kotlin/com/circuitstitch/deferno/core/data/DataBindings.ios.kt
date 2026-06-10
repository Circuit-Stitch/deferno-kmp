package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.data.account.AccountDataStore
import com.circuitstitch.deferno.core.data.account.AccountRegistry
import com.circuitstitch.deferno.core.data.account.InMemoryAccountRegistry
import com.circuitstitch.deferno.core.data.account.NoOpAccountDataStore
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.data.auth.IosBrowserAuthenticator
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import platform.UIKit.UIDevice
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS AppScope actuals (ADR-0014): an in-memory roster (a persistent Keychain/file-backed registry is
 * a follow-up) and the no-op data store. iOS per-Account isolation is enforced by the per-Account
 * encrypted DB file + Keychain key (the [com.circuitstitch.deferno.core.database] iOS driver), not a
 * separate sidecar wipe.
 */
@ContributesTo(AppScope::class)
interface IosDataBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun accountRegistry(): AccountRegistry = InMemoryAccountRegistry()

    @Provides
    @SingleIn(AppScope::class)
    fun accountDataStore(): AccountDataStore = NoOpAccountDataStore

    /** The system-browser OAuth leg (ADR-0026); the Swift app forwards the redirect via IosAuthRedirectInbox. */
    @Provides
    @SingleIn(AppScope::class)
    fun browserAuthenticator(): BrowserAuthenticator = IosBrowserAuthenticator()

    /** Tags a minted token to this device (ADR-0026). */
    @Provides
    @SingleIn(AppScope::class)
    fun deviceName(): DeviceName = DeviceName("Deferno iOS — ${UIDevice.currentDevice.name}")
}
