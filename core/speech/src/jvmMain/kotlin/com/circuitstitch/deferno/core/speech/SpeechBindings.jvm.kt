package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.scopes.AppScope
import com.circuitstitch.deferno.core.sidecar.SidecarClient
import com.circuitstitch.deferno.core.sidecar.SidecarSocketPath
import com.circuitstitch.deferno.core.sidecar.SidecarTokenSource
import com.circuitstitch.deferno.core.sidecar.unixSocketSidecarClient
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
 *   simply keeps whisper).
 *
 * The [SidecarClient] is itself an AppScope singleton (one socket, one handshake, shared by the
 * engine now and the #120 permission ports / future capability ports later), dialing the per-OS
 * well-known path with the out-of-band token ([SidecarTokenSource]; an unprovisioned token resolves
 * empty and the handshake simply fails → graceful degradation, never an error). The engine
 * [[App setting]] is backed by `java.util.prefs` (the cross-desktop store).
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
    fun sidecarSpeechEngine(client: SidecarClient): SpeechToText = SidecarSpeechToText(client)

    @Provides
    @SingleIn(AppScope::class)
    fun sidecarClient(): SidecarClient =
        unixSocketSidecarClient(SidecarSocketPath.default(), SidecarTokenSource.resolve().orEmpty())

    @Provides
    @SingleIn(AppScope::class)
    fun speechEnginePreference(): SpeechEnginePreference =
        SettingsSpeechEnginePreference(
            PreferencesSettings(Preferences.userRoot().node("com/circuitstitch/deferno/speech")),
        )
}
