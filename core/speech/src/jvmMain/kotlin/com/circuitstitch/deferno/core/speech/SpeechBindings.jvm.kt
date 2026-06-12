package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.scopes.AppScope
import com.circuitstitch.deferno.core.sidecar.DefaultSidecarSpeechPort
import com.circuitstitch.deferno.core.sidecar.SidecarClient
import com.circuitstitch.deferno.core.sidecar.SidecarPermissionPort
import com.russhwolf.settings.PreferencesSettings
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.prefs.Preferences

/**
 * Desktop (JVM) speech bindings (ADR-0018), AppScope process-singletons. Two engines feed the
 * `Set<SpeechToText>` multibinding:
 *
 * - the on-device whisper.cpp baseline ([WhisperSpeechToText] over the `whisper-jni` artifact +
 *   `TargetDataLine` capture, #94) — the always-available floor, resolving the installer-bundled
 *   `small.en` weights via [BundledModelLocator] (ADR-0019); it reports
 *   [UnavailableReason.ModelMissing] until those weights are present, so the `Set<SpeechToText>` is
 *   never empty and the [SpeechToTextSelector] resolves on every target;
 * - the Sidecar-hosted native fast path ([SidecarSpeechToText], #119, ADR-0024) — ranked above the
 *   floor, Available only when a native Helper is bound at the well-known socket and reports the
 *   speech engine genuinely ready (so on Linux/Windows, or a Mac without the Helper, the selector
 *   simply keeps whisper). It speaks through its [DefaultSidecarSpeechPort] over the **process-wide
 *   [SidecarClient]** — provided by core/di's `SidecarBindings` (one socket, one handshake, shared
 *   with the #120 permission ports and future capability ports), not owned by this module.
 *
 * The engine [[App setting]] is backed by `java.util.prefs` (the cross-desktop store).
 */
@ContributesTo(AppScope::class)
interface JvmSpeechBindings {
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun jvmSpeechEngine(): SpeechToText = WhisperSpeechToText(modelLocator = BundledModelLocator())

    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun sidecarSpeechEngine(client: SidecarClient, permissions: SidecarPermissionPort): SpeechToText =
        SidecarSpeechToText(DefaultSidecarSpeechPort(client), permissions)

    /**
     * The dictation-permission settings deep-link (#120): live introspection over the same
     * process-wide [SidecarPermissionPort] (from core/di's `SidecarBindings`), picking the blocked
     * capability's macOS Privacy pane at click time. Null off-macOS — the affordance simply no-ops.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun dictationPermissionSettings(permissions: SidecarPermissionPort): DictationPermissionSettings =
        SidecarDictationPermissionSettings(permissions)

    @Provides
    @SingleIn(AppScope::class)
    fun speechEnginePreference(): SpeechEnginePreference =
        SettingsSpeechEnginePreference(
            PreferencesSettings(Preferences.userRoot().node("com/circuitstitch/deferno/speech")),
        )
}
