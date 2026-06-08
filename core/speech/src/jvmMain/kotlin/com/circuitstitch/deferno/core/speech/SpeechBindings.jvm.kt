package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.PreferencesSettings
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.prefs.Preferences

/**
 * Desktop (JVM) speech bindings (ADR-0018). No desktop engine has landed yet (the `whisper-jni` baseline
 * is a follow-up), so the registered engine is the [UnavailableSpeechToText] floor — the `Set<SpeechToText>`
 * is never empty and the [SpeechToTextSelector] resolves on every target. The engine [[App setting]] is
 * backed by `java.util.prefs` (the cross-desktop store).
 */
@ContributesTo(AppScope::class)
interface JvmSpeechBindings {
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun jvmSpeechEngine(): SpeechToText = UnavailableSpeechToText

    @Provides
    @SingleIn(AppScope::class)
    fun speechEnginePreference(): SpeechEnginePreference =
        SettingsSpeechEnginePreference(
            PreferencesSettings(Preferences.userRoot().node("com/circuitstitch/deferno/speech")),
        )
}
