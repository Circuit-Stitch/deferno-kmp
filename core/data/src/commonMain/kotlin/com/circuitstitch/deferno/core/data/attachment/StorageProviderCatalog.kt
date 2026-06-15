package com.circuitstitch.deferno.core.data.attachment

/**
 * The device-local **storage-provider choice** the Settings Destination renders (#210): the providers a
 * *user/task* attachment can be stored in, plus the current device-local selection. The direct analogue of
 * `SpeechEngineCatalog` / `InferenceEngineCatalog`, minus the entitlement gate (storage has none). An
 * **[[App setting]]**: device-local (persisted via [StorageProviderPreference]), never synced, never crossing
 * Accounts (AppScope, ADR-0014).
 *
 * [options] is a static list: on-device (the offline-first default) and the Deferno backend are selectable
 * now; the user-owned cloud providers are [StorageProviderAvailability.ComingLater] (shown disabled until
 * their OAuth lands, #210). Feedback attachments are unaffected — they always use the backend.
 */
class StorageProviderCatalog(
    private val preference: StorageProviderPreference,
) {
    /** The selectable providers — on-device + backend available, the user-owned cloud providers coming-later. */
    fun options(): List<StorageProviderOption> = listOf(
        StorageProviderOption(StorageProviderId.OnDevice, StorageProviderAvailability.Available),
        StorageProviderOption(StorageProviderId.DefernoBackend, StorageProviderAvailability.Available),
        StorageProviderOption(StorageProviderId.Dropbox, StorageProviderAvailability.ComingLater),
        StorageProviderOption(StorageProviderId.GoogleDrive, StorageProviderAvailability.ComingLater),
    )

    /** The current device-local choice — defaults to [StorageProviderId.OnDevice]. */
    fun selected(): StorageProviderId = preference.selectedProvider()

    /** Persist the device-local choice. **Never** synced to the backend (App setting). */
    fun select(id: StorageProviderId) = preference.setSelectedProvider(id)

    companion object {
        /**
         * The default catalog the shell/Settings tests build with when no platform preference is wired: the
         * same static options over an in-memory selection (the analogue of `InferenceEngineCatalog.Inert`,
         * except on-device is always available, so the Storage row is always shown).
         */
        val Inert: StorageProviderCatalog = StorageProviderCatalog(InMemoryStorageProviderPreference())
    }
}
