package com.circuitstitch.deferno.core.di

import android.content.Context
import com.circuitstitch.deferno.core.scopes.AppScope
import com.circuitstitch.deferno.core.scopes.PlatformContext
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Unwraps the application [Context] from the [PlatformContext] handle the Android app creates the
 * AppScope graph with (ADR-0014). Every Android AppScope binding that needs a `Context` — the
 * Keystore vault, the SharedPreferences roster, the SQLCipher key store — resolves it here, so the
 * platform handle stays opaque to common code.
 */
@ContributesTo(AppScope::class)
interface AndroidContextBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun context(platform: PlatformContext): Context = platform.context
}
