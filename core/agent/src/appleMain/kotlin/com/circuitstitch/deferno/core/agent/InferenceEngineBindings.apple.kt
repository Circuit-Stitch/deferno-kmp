package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.NSUserDefaultsSettings
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Apple (iOS + macOS) inference-engine binding (#150, ADR-0037 / #266). On-device inference now defaults ON
 * (the ADR-0027 amendment): iOS selects **Apple Foundation Models** out of the box, so a Brain dump extracts
 * on an Apple-Intelligence device and salvages elsewhere — never silently producing nothing. The choice
 * persists device-locally through `NSUserDefaultsSettings` so it survives relaunch (the swap the prior
 * in-memory placeholder anticipated). The actual Foundation Models engine registers under this id from the
 * app layer (#269); until then the selection routes to NotConfigured and the Salvage draft covers it. Cloud
 * stays explicit opt-in + per-Account entitlement (never selected silently).
 */
@ContributesTo(AppScope::class)
interface AppleInferenceEngineBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEnginePreference(): InferenceEnginePreference =
        SettingsInferenceEnginePreference(
            NSUserDefaultsSettings.Factory().create("deferno_agent"),
            default = InferenceEngineId.OnDeviceFoundationModels,
        )
}
