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
 * The engine is contributed `@IntoSet` into the `Set<SpeechToText>` the [SpeechToTextSelector] ranks: the
 * real on-device whisper.cpp engine ([WhisperSpeechToText] — NDK/CMake/JNI + AudioRecord). It reports
 * [UnavailableReason.ModelMissing] until the model is present; the [WhisperModelLocator] is an L1
 * placeholder returning `null`, and the Play Asset Delivery-backed locator (ADR-0019) is wired in #92 L2.
 */
@ContributesTo(AppScope::class)
interface AndroidSpeechBindings {
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun androidSpeechEngine(): SpeechToText =
        WhisperSpeechToText(modelLocator = WhisperModelLocator { null })

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
