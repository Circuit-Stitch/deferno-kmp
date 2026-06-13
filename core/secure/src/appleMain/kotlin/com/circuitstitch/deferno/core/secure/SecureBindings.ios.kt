package com.circuitstitch.deferno.core.secure

import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS [SecretVault] binding (ADR-0009/0014): the Keychain-backed vault, an AppScope process-singleton.
 * `@Provides` (not `@Inject`) because the impl carries a default-arg constructor param (service name).
 */
@ContributesTo(AppScope::class)
interface IosSecureBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun secretVault(): SecretVault = KeychainSecretVault()
}
