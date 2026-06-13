package com.circuitstitch.deferno.core.agent

import com.russhwolf.settings.Settings

/**
 * The device-local **inference-engine choice** (#150, ADR-0027): which engine the [[Agent]] runs on. An
 * **[[App setting]]** — stored device-locally, **never synced**, never crossing Accounts — because engine
 * availability is per-device (an on-device runtime on one device may be absent on another; a cloud engine
 * is entitlement-gated per Account). The default is [InferenceEngineId.Off]: AI is never forced on (a
 * later onboarding step asks whether to use it at all). Mirrors core/speech `SpeechEnginePreference`.
 */
interface InferenceEnginePreference {
    /** The chosen engine — defaults to [InferenceEngineId.Off] when none has been set. */
    fun selectedEngine(): InferenceEngineId

    /** Persist the chosen engine device-locally. Never synced to the backend (App setting). */
    fun setSelectedEngine(id: InferenceEngineId)
}

/**
 * A non-persistent [InferenceEnginePreference] for tests, previews, and the targets that don't surface the
 * setting yet (#150 is Android-only). **Measured** (commonTest) — defaults to [InferenceEngineId.Off] and
 * round-trips a set value.
 */
class InMemoryInferenceEnginePreference(
    initial: InferenceEngineId = InferenceEngineId.Off,
) : InferenceEnginePreference {
    private var current: InferenceEngineId = initial
    override fun selectedEngine(): InferenceEngineId = current
    override fun setSelectedEngine(id: InferenceEngineId) {
        current = id
    }
}

/**
 * The production [InferenceEnginePreference] over a multiplatform-settings [Settings] (#150): one commonMain
 * impl over SharedPreferences (Android), `java.util.prefs` (JVM), or NSUserDefaults (Apple) — each platform's
 * bindings supply the platform-backed [Settings]. Excluded from the coverage gate (a thin store adapter
 * exercised through the platform store, not the headless JVM gate, ADR-0006).
 */
class SettingsInferenceEnginePreference(
    private val settings: Settings,
    private val default: InferenceEngineId = InferenceEngineId.Off,
) : InferenceEnginePreference {
    override fun selectedEngine(): InferenceEngineId =
        settings.getStringOrNull(KEY)?.let(::InferenceEngineId) ?: default

    override fun setSelectedEngine(id: InferenceEngineId) {
        settings.putString(KEY, id.value)
    }

    private companion object {
        const val KEY = "agent.engine"
    }
}
