package com.circuitstitch.deferno.core.speech

import com.russhwolf.settings.Settings

/**
 * The device-local **speech-engine choice** (ADR-0018): which [SpeechToText] engine the user prefers.
 * It is an **[[App setting]]** — stored device-locally, **never synced**, never crossing Accounts —
 * because engine availability is per-device (the same engine may not exist on another device). The
 * default is [SpeechEngineId.Automatic] — let the selector rank-pick the best available engine — until
 * the user explicitly chooses one via the Settings [[Destination]].
 *
 * The [SpeechToTextSelector] reads this to bias selection toward the chosen engine when it is available,
 * falling back to rank otherwise.
 */
interface SpeechEnginePreference {
    /** The chosen engine — defaults to [SpeechEngineId.Automatic] (rank-pick, ADR-0018) when none has been set. */
    fun preferredEngine(): SpeechEngineId

    /** Persist the chosen engine device-locally. Never synced to the backend (App setting, ADR-0018). */
    fun setPreferredEngine(id: SpeechEngineId)
}

/**
 * A non-persistent [SpeechEnginePreference] for tests and previews (the analogue of `InMemorySecretVault`).
 * **Measured** (commonTest) — defaults to [SpeechEngineId.Automatic] and round-trips a set value.
 */
class InMemorySpeechEnginePreference(
    initial: SpeechEngineId = SpeechEngineId.Automatic,
) : SpeechEnginePreference {
    private var current: SpeechEngineId = initial
    override fun preferredEngine(): SpeechEngineId = current
    override fun setPreferredEngine(id: SpeechEngineId) {
        current = id
    }
}

/**
 * The production [SpeechEnginePreference] over a multiplatform-settings [Settings] (ADR-0018): one
 * commonMain impl over SharedPreferences (Android), `java.util.prefs` (JVM), or NSUserDefaults (iOS) —
 * each platform's `SpeechBindings` supplies the platform-backed [Settings]. Excluded from the coverage
 * gate (a thin store adapter exercised through the platform store, not the headless JVM gate, ADR-0006).
 */
class SettingsSpeechEnginePreference(
    private val settings: Settings,
    private val default: SpeechEngineId = SpeechEngineId.Automatic,
) : SpeechEnginePreference {
    override fun preferredEngine(): SpeechEngineId =
        settings.getStringOrNull(KEY)?.let(::SpeechEngineId) ?: default

    override fun setPreferredEngine(id: SpeechEngineId) {
        settings.putString(KEY, id.value)
    }

    private companion object {
        const val KEY = "speech.engine"
    }
}
