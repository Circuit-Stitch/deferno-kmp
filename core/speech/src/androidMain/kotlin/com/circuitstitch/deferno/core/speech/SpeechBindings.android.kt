package com.circuitstitch.deferno.core.speech

import android.content.Context
import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.SharedPreferencesSettings
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android speech bindings (ADR-0018), AppScope process-singletons. The application `Context` is resolved
 * from the AppScope graph (the `PlatformContext` unwrap in core:di), exactly as the secure vault does.
 *
 * The engine is contributed `@IntoSet` into the `Set<SpeechToText>` the [SpeechToTextSelector] ranks.
 * **L0 wires the always-unavailable floor as a placeholder** so the multibinding resolves on every target
 * while the rest of the slice lands; the real on-device whisper.cpp engine (NDK/CMake/JNI + AudioRecord)
 * replaces it in #92 L1.
 */
@ContributesTo(AppScope::class)
interface AndroidSpeechBindings {
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun androidSpeechEngine(): SpeechToText = UnavailableSpeechToText

    /**
     * The device-local speech-engine choice ([[App setting]], ADR-0018), SharedPreferences-backed. The
     * file is app-private; it holds only the engine id, never audio or Transcript text.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun speechEnginePreference(context: Context): SpeechEnginePreference =
        SettingsSpeechEnginePreference(
            SharedPreferencesSettings(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        )
}

private const val PREFS_NAME = "deferno_speech"
