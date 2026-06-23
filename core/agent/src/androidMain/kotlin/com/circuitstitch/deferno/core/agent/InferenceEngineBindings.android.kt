package com.circuitstitch.deferno.core.agent

import android.content.Context
import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.SharedPreferencesSettings
import me.tatarka.inject.annotations.IntoMap
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android inference-engine bindings (#150, ADR-0027): the device-local choice ([[App setting]],
 * SharedPreferences-backed) plus the on-device deterministic floor — registered both as a selectable
 * engine in the catalog (`@IntoSet` descriptor) and as a routable engine ([RoutingInferenceEngine]'s
 * `@IntoMap` entry), mirroring how `AndroidSpeechBindings` contributes the whisper engine. The
 * application `Context` is resolved from the AppScope graph (the `PlatformContext` unwrap in core:di),
 * exactly as speech + the secure vault do. The prefs file is app-private; it holds only the engine id,
 * never a prompt or any Item text.
 */
@ContributesTo(AppScope::class)
interface AndroidInferenceEngineBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEnginePreference(context: Context): InferenceEnginePreference =
        SettingsInferenceEnginePreference(
            SharedPreferencesSettings(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
            // On-device defaults ON (ADR-0027 amendment / #266): the ungated shacl floor is selected out of
            // the box, so Brain dump produces drafts with no manual engine pick. Cloud stays explicit opt-in.
            default = InferenceEngineId.OnDeviceFloor,
        )

    /** The deterministic floor as a selectable engine in the Settings catalog — OnDevice, so ungated. */
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun floorEngineDescriptor(): InferenceEngineDescriptor =
        InferenceEngineDescriptor(InferenceEngineId.OnDeviceFloor, InferenceEngineOrigin.OnDevice)

    /** The floor engine, keyed by its id so [RoutingInferenceEngine] runs it when it is the selection. */
    @Provides
    @IntoMap
    @SingleIn(AppScope::class)
    fun floorInferenceEngine(): Pair<InferenceEngineId, InferenceEngine> =
        InferenceEngineId.OnDeviceFloor to ShaclFloorInferenceEngine()
}

private const val PREFS_NAME = "deferno_agent"
