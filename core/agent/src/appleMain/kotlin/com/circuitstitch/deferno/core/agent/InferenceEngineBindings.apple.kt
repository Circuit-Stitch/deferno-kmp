package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Apple (iOS + macOS) inference-engine binding (#150). No Apple client surfaces the inference-engine
 * setting yet, and entitlement is fake-false graph-wide, so the choice is never read on these targets — an
 * in-memory [InferenceEnginePreference] suffices (no NSUserDefaults store for a value nothing consults).
 * Swap for a `SettingsInferenceEnginePreference` over `NSUserDefaultsSettings` when an Apple Settings row lands.
 */
// ponytail: in-memory until an Apple client surfaces the setting — nothing reads the value here yet.
@ContributesTo(AppScope::class)
interface AppleInferenceEngineBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEnginePreference(): InferenceEnginePreference = InMemoryInferenceEnginePreference()
}
