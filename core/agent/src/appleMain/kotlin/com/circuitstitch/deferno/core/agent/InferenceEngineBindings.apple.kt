package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.NSUserDefaultsSettings
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Apple (iOS + macOS) inference-engine binding (#150, ADR-0037 / #266). On-device inference now defaults ON
 * (the ADR-0027 amendment): both Apple targets select **Apple Foundation Models** out of the box, so iOS's
 * Brain dump extracts on an Apple-Intelligence device and salvages elsewhere — never silently producing
 * nothing. The choice persists device-locally through `NSUserDefaultsSettings` so it survives relaunch (the
 * swap the prior in-memory placeholder anticipated). The engine itself registers under this id from each
 * app layer — `IosFoundationModelsBindings` (#269) and `MacosAgentBindings` (ADR-0029 Phase 3), both stable
 * forwarders the Swift adapter is installed into at launch; a device without Apple Intelligence answers
 * NotConfigured and the Salvage draft covers it. macOS routes the same default, but has no Brain-dump
 * pipeline reading the seam yet (#368). Cloud stays explicit opt-in + per-Account entitlement (never
 * selected silently).
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
