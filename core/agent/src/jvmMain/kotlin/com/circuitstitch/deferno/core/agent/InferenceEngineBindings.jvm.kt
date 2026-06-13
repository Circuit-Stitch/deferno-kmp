package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Desktop (JVM) inference-engine binding (#150). The inference-engine setting isn't surfaced on desktop yet
 * (#150 is Android-only) and entitlement is fake-false graph-wide, so the choice is never read on this
 * target — an in-memory [InferenceEnginePreference] suffices, with no `java.util.prefs` store to persist a
 * value nothing consults. Swap for a `SettingsInferenceEnginePreference` when a desktop Settings row lands.
 */
// ponytail: in-memory until desktop surfaces the setting — nothing reads the value here yet.
@ContributesTo(AppScope::class)
interface JvmInferenceEngineBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEnginePreference(): InferenceEnginePreference = InMemoryInferenceEnginePreference()
}
