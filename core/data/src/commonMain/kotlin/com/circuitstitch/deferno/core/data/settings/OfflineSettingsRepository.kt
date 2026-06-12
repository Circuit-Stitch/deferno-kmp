package com.circuitstitch.deferno.core.data.settings

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.SettingsMutation
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The offline-first [SettingsRepository] (ADR-0001, #72). It deliberately mirrors
 * [com.circuitstitch.deferno.core.data.plan.OfflinePlanRepository] but is simpler still — a single
 * settings row, no ordering, no hydration:
 *
 * **Resolve ([observeSettings]).** Maps the local store's nullable `Flow` to a non-null stream by
 * seeding [UserSettings.Default] until the cache is populated, so the theme always has a value.
 *
 * **Reconcile ([refresh]).** `GET /auth/me/settings` is a full snapshot; a refresh pulls it and
 * upserts the singleton row. An [RemoteSnapshot.Unavailable] pull skips the upsert,
 * leaving the cached settings intact (offline-first). The upsert is also skipped while a settings
 * mutation is still **pending in the outbox** (#143): the server snapshot predates the un-synced
 * local change, so under LWW the optimistic row is newer — overwriting it would revert the user's
 * choice on every cold start. The row converges via the post-flush reconcile once the queued
 * `PATCH` lands (or, if the server rejects it terminally, via the next clean refresh).
 */
class OfflineSettingsRepository(
    private val localStore: SettingsLocalStore,
    private val remoteSource: SettingsRemoteSource,
    private val outbox: OutboxStore,
) : SettingsRepository {

    override fun observeSettings(): Flow<UserSettings> =
        localStore.observeSettings().map { it ?: UserSettings.Default }

    override suspend fun refresh() {
        val remote = when (val result = remoteSource.fetchSettings()) {
            is RemoteSnapshot.Available -> result.value
            RemoteSnapshot.Unavailable -> return
        }
        // Checked after the fetch (not before) to shrink the enqueue-during-fetch race window.
        if (outbox.pending().any { it.target == SettingsMutation.TARGET }) return
        localStore.upsert(remote)
    }
}
