package com.circuitstitch.deferno.core.data.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The device-local storage-provider selector (#210): the catalog's static options (on-device + the Deferno
 * backend available, the user-owned cloud providers coming-later), the selection round-trip through the
 * preference, and the in-memory preference's default. Measured (commonMain) — the platform Settings adapter
 * ([SettingsStorageProviderPreference]) is coverage-excluded, exercised through the real platform store.
 */
class StorageProviderCatalogTest {

    @Test
    fun optionsListAvailableAndComingLaterProviders() {
        val options = StorageProviderCatalog(InMemoryStorageProviderPreference()).options()

        assertEquals(
            listOf(
                StorageProviderId.OnDevice,
                StorageProviderId.DefernoBackend,
                StorageProviderId.Dropbox,
                StorageProviderId.GoogleDrive,
            ),
            options.map { it.id },
        )
        assertIs<StorageProviderAvailability.Available>(
            options.first { it.id == StorageProviderId.OnDevice }.availability,
        )
        assertIs<StorageProviderAvailability.Available>(
            options.first { it.id == StorageProviderId.DefernoBackend }.availability,
        )
        assertIs<StorageProviderAvailability.ComingLater>(
            options.first { it.id == StorageProviderId.GoogleDrive }.availability,
        )
    }

    @Test
    fun defaultsToOnDevice() {
        assertEquals(StorageProviderId.OnDevice, StorageProviderCatalog(InMemoryStorageProviderPreference()).selected())
        assertEquals(StorageProviderId.OnDevice, StorageProviderCatalog.Inert.selected())
    }

    @Test
    fun selectPersistsThroughThePreference() {
        val preference = InMemoryStorageProviderPreference()
        val catalog = StorageProviderCatalog(preference)

        catalog.select(StorageProviderId.DefernoBackend)

        assertEquals(StorageProviderId.DefernoBackend, catalog.selected())
        assertEquals(StorageProviderId.DefernoBackend, preference.selectedProvider())
    }

    @Test
    fun inMemoryPreferenceRespectsInitialAndRoundTrips() {
        assertEquals(
            StorageProviderId.Dropbox,
            InMemoryStorageProviderPreference(StorageProviderId.Dropbox).selectedProvider(),
        )
        val pref = InMemoryStorageProviderPreference()
        pref.setSelectedProvider(StorageProviderId.GoogleDrive)
        assertEquals(StorageProviderId.GoogleDrive, pref.selectedProvider())
    }
}
