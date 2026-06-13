package com.circuitstitch.deferno.core.agent

import android.content.Context
import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.SharedPreferencesSettings
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android inference-engine binding (#150, ADR-0027): the device-local choice ([[App setting]]),
 * SharedPreferences-backed. The application `Context` is resolved from the AppScope graph (the
 * `PlatformContext` unwrap in core:di), exactly as speech + the secure vault do. The file is app-private;
 * it holds only the engine id, never a prompt or any Item text.
 */
@ContributesTo(AppScope::class)
interface AndroidInferenceEngineBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEnginePreference(context: Context): InferenceEnginePreference =
        SettingsInferenceEnginePreference(
            SharedPreferencesSettings(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        )
}

private const val PREFS_NAME = "deferno_agent"
