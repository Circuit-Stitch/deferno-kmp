package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.NSUserDefaultsSettings
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS speech bindings (ADR-0018). The Apple `SpeechTranscriber` / `whisper.xcframework` engines are a
 * follow-up (the iOS View is SwiftUI and the framework link is macOS-only), so the registered engine is
 * the [UnavailableSpeechToText] floor — keeping the `Set<SpeechToText>` non-empty so the merged graph
 * resolves on the iOS targets. The engine [[App setting]] is backed by NSUserDefaults.
 */
@ContributesTo(AppScope::class)
interface IosSpeechBindings {
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun iosSpeechEngine(): SpeechToText = UnavailableSpeechToText

    @Provides
    @SingleIn(AppScope::class)
    fun speechEnginePreference(): SpeechEnginePreference =
        SettingsSpeechEnginePreference(NSUserDefaultsSettings.Factory().create("deferno_speech"))
}
