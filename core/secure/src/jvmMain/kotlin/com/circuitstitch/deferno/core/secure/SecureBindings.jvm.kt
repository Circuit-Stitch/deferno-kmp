package com.circuitstitch.deferno.core.secure

import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Desktop (JVM) [SecretVault] binding (ADR-0009/0014): the OS-keychain-backed vault, an AppScope
 * process-singleton. Built via the `create()` factory, which maps a missing OS keychain backend to a
 * `SecureStorageException` — so this is lazily constructed (kotlin-inject only builds it when the
 * vault is first resolved), keeping headless graph smoke tests from touching the host keychain.
 */
@ContributesTo(AppScope::class)
interface JvmSecureBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun secretVault(): SecretVault = DesktopSecretVault.create()
}
