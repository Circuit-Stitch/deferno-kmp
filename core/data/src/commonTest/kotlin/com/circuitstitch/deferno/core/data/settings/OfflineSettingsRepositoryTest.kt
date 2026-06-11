package com.circuitstitch.deferno.core.data.settings

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.SetTheme
import com.circuitstitch.deferno.core.data.outbox.SetWorkingState
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The offline-first behaviour of [OfflineSettingsRepository] (#72, ADR-0001), against the in-memory
 * fakes. Covers: [observeSettings] emits from the local store `Flow` only (never the network),
 * seeding [UserSettings.Default] before the first refresh; [refresh] upserts the remote snapshot; a
 * failed remote (null) leaves the cache intact; and the #143 hardening — a refresh never overwrites
 * the row while an un-synced settings mutation is pending in the outbox (LWW: the local optimistic
 * change is newer than the server snapshot). Mirrors [com.circuitstitch.deferno.core.data.plan.OfflinePlanRepositoryTest].
 */
class OfflineSettingsRepositoryTest {

    private val t0 = Instant.parse("2026-06-07T12:00:00Z")

    private fun repo(
        local: FakeSettingsLocalStore = FakeSettingsLocalStore(),
        remote: FakeSettingsRemoteSource = FakeSettingsRemoteSource(),
        outbox: FakeOutboxStore = FakeOutboxStore(),
    ) = OfflineSettingsRepository(local, remote, outbox)

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
    fun refreshDoesNotClobberAnUnsyncedSettingsChange() = runTest {
        // The #143 repro: the user picked a theme (optimistic local apply + queued PATCH), then a
        // cold-start reconcile pulls the server snapshot — which predates the un-synced change.
        val chosen = UserSettings(themeFamily = ThemeFamily.Mono, themeMode = ThemeMode.Dark)
        val local = FakeSettingsLocalStore(initial = chosen)
        val remote = FakeSettingsRemoteSource(next = UserSettings.Default) // stale server truth
        val outbox = FakeOutboxStore().apply {
            val pending = SetTheme(ThemeFamily.Mono, ThemeMode.Dark)
            enqueue(pending.target, pending.toRequest(), t0)
        }

        repo(local, remote, outbox).refresh()

        // The pending mutation is newer than the snapshot (LWW): the optimistic row stands.
        assertEquals(chosen, local.current)
    }

    @Test
    fun refreshStillUpsertsWhenOnlyOtherTargetsArePending() = runTest {
        // A queued TASK write must not freeze the settings reconcile — only a settings-target
        // mutation marks the settings row as locally newer.
        val local = FakeSettingsLocalStore()
        val remote = FakeSettingsRemoteSource(next = UserSettings(themeFamily = ThemeFamily.Mono))
        val outbox = FakeOutboxStore().apply {
            val pending = SetWorkingState(TaskId("a"), WorkingState.Done)
            enqueue(pending.target, pending.toRequest(), t0)
        }

        repo(local, remote, outbox).refresh()

        assertEquals(ThemeFamily.Mono, local.current?.themeFamily)
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
