package com.circuitstitch.deferno.core.data.settings

import app.cash.turbine.test
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The offline-first behaviour of [OfflineSettingsRepository] (#72, ADR-0001), against the in-memory
 * fakes. Covers: [observeSettings] emits from the local store `Flow` only (never the network),
 * seeding [UserSettings.Default] before the first refresh; [refresh] upserts the remote snapshot; and
 * a failed remote (null) leaves the cache intact. Mirrors [com.circuitstitch.deferno.core.data.plan.OfflinePlanRepositoryTest].
 */
class OfflineSettingsRepositoryTest {

    private fun repo(
        local: FakeSettingsLocalStore = FakeSettingsLocalStore(),
        remote: FakeSettingsRemoteSource = FakeSettingsRemoteSource(),
    ) = OfflineSettingsRepository(local, remote)

    @Test
    fun observeSeedsTheDefaultBeforeAnyRefresh() = runTest {
        repo().observeSettings().test {
            assertEquals(UserSettings.Default, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshUpsertsTheRemoteSnapshotIntoTheCache() = runTest {
        val local = FakeSettingsLocalStore()
        val remote = FakeSettingsRemoteSource(
            next = UserSettings(themeFamily = ThemeFamily.Mono, themeMode = ThemeMode.Dark, trackingEnabled = true),
        )

        repo(local, remote).refresh()

        assertEquals(ThemeFamily.Mono, local.current?.themeFamily)
        assertEquals(ThemeMode.Dark, local.current?.themeMode)
        assertEquals(true, local.current?.trackingEnabled)
    }

    @Test
    fun observeReadsFromTheLocalStoreOnly_notTheNetwork() = runTest {
        val cached = UserSettings(themeFamily = ThemeFamily.Mono)
        val local = FakeSettingsLocalStore(initial = cached)
        val remote = FakeSettingsRemoteSource(next = UserSettings(themeFamily = ThemeFamily.Deferno))

        repo(local, remote).observeSettings().test {
            // The cached row is observed; the remote is never consulted by a read.
            assertEquals(cached, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, remote.fetchCount)
    }

    @Test
    fun aFailedRefreshLeavesTheCacheIntact() = runTest {
        val cached = UserSettings(themeFamily = ThemeFamily.Mono)
        val local = FakeSettingsLocalStore(initial = cached)
        val remote = FakeSettingsRemoteSource(next = null) // offline / failed pull

        repo(local, remote).refresh()

        assertEquals(cached, local.current)
    }

    @Test
    fun observeReEmitsAfterARefresh() = runTest {
        val local = FakeSettingsLocalStore()
        val remote = FakeSettingsRemoteSource()
        val repository = repo(local, remote)

        repository.observeSettings().test {
            assertEquals(UserSettings.Default, awaitItem())

            remote.next = UserSettings(themeFamily = ThemeFamily.Mono, themeMode = ThemeMode.Dark)
            repository.refresh()

            val updated = awaitItem()
            assertEquals(ThemeFamily.Mono, updated.themeFamily)
            assertEquals(ThemeMode.Dark, updated.themeMode)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
