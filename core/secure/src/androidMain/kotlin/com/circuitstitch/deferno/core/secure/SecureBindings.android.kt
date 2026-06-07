package com.circuitstitch.deferno.core.secure

import android.content.Context
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android [SecretVault] binding (ADR-0009/0014): the production Keystore-backed vault, an AppScope
 * process-singleton. The application `Context` is resolved from the AppScope graph (the
 * `PlatformContext` unwrap in core:di). `@Provides` (not `@Inject`) because the impl carries
 * default-arg constructor params (key alias / prefs name).
 */
@ContributesTo(AppScope::class)
interface AndroidSecureBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun secretVault(context: Context): SecretVault = AndroidKeystoreSecretVault(context)
}
