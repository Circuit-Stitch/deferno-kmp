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
 * real on-device whisper.cpp engine ([WhisperSpeechToText] — NDK/CMake/JNI + AudioRecord), resolving its
 * model from the Play Asset Delivery install-time pack ([PlayAssetDeliveryModelLocator], ADR-0019). It
 * reports [UnavailableReason.ModelMissing] until that pack's weights are present on device.
 */
@ContributesTo(AppScope::class)
interface AndroidSpeechBindings {
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun androidSpeechEngine(context: Context): SpeechToText =
        WhisperSpeechToText(modelLocator = PlayAssetDeliveryModelLocator(context))

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

    /**
     * No shared-layer permission deep-link on Android (#120): the View owns the RECORD_AUDIO prompt and
     * the app-settings intent (`MainActivity.onOpenOsAppSettings`), so the shared seam has nothing to open.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun dictationPermissionSettings(): DictationPermissionSettings = DictationPermissionSettings { null }
}

private const val PREFS_NAME = "deferno_speech"
