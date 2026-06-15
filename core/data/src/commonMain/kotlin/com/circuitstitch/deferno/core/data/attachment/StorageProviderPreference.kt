package com.circuitstitch.deferno.core.data.attachment

import com.russhwolf.settings.Settings

/**
 * The device-local **storage-provider choice** (#210) for *user/task* attachments: where new attachments
 * are stored. An **[[App setting]]** — stored device-locally, **never synced** — because storage is a
 * device/identity concern (a cloud provider is the user's own account, not Deferno's). The default is
 * [StorageProviderId.OnDevice] (offline-first). Mirrors core/agent `InferenceEnginePreference`.
 */
interface StorageProviderPreference {
    /** The chosen provider — defaults to [StorageProviderId.OnDevice] when none has been set. */
    fun selectedProvider(): StorageProviderId

    /** Persist the chosen provider device-locally. Never synced to the backend (App setting). */
    fun setSelectedProvider(id: StorageProviderId)
}

/**
 * A non-persistent [StorageProviderPreference] for tests, previews, and the targets that don't surface the
 * setting yet. **Measured** (commonTest) — defaults to [StorageProviderId.OnDevice] and round-trips a set value.
 */
class InMemoryStorageProviderPreference(
    initial: StorageProviderId = StorageProviderId.OnDevice,
) : StorageProviderPreference {
    private var current: StorageProviderId = initial
    override fun selectedProvider(): StorageProviderId = current
    override fun setSelectedProvider(id: StorageProviderId) {
        current = id
    }
}

/**
 * The production [StorageProviderPreference] over a multiplatform-settings [Settings] (#210): one commonMain
 * impl over SharedPreferences (Android), `java.util.prefs` (JVM), or NSUserDefaults (Apple) — each platform's
 * bindings supply the platform-backed [Settings]. Excluded from the coverage gate (a thin store adapter
 * exercised through the platform store, not the headless JVM gate, ADR-0006) — like `SettingsInferenceEnginePreference`.
 */
class SettingsStorageProviderPreference(
    private val settings: Settings,
    private val default: StorageProviderId = StorageProviderId.OnDevice,
) : StorageProviderPreference {
    override fun selectedProvider(): StorageProviderId =
        settings.getStringOrNull(KEY)?.let(::StorageProviderId) ?: default

    override fun setSelectedProvider(id: StorageProviderId) {
        settings.putString(KEY, id.value)
    }

    private companion object {
        const val KEY = "storage.provider"
    }
}
