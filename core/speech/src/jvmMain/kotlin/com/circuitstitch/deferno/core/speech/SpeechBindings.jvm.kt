package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.PreferencesSettings
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.prefs.Preferences

/**
 * Desktop (JVM) speech bindings (ADR-0018), AppScope process-singletons. The registered engine is the
 * on-device whisper.cpp baseline ([WhisperSpeechToText] over the `whisper-jni` artifact + `TargetDataLine`
 * capture, #94), resolving the installer-bundled `small.en` weights via [BundledModelLocator] (ADR-0019).
 * It reports [UnavailableReason.ModelMissing] until those weights are present, so the `Set<SpeechToText>`
 * is never empty and the [SpeechToTextSelector] resolves on every target. The engine [[App setting]] is
 * backed by `java.util.prefs` (the cross-desktop store).
 */
@ContributesTo(AppScope::class)
interface JvmSpeechBindings {
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun jvmSpeechEngine(): SpeechToText = WhisperSpeechToText(modelLocator = BundledModelLocator())

    @Provides
    @SingleIn(AppScope::class)
    fun speechEnginePreference(): SpeechEnginePreference =
        SettingsSpeechEnginePreference(
            PreferencesSettings(Preferences.userRoot().node("com/circuitstitch/deferno/speech")),
        )
}
