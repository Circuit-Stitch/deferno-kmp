package com.circuitstitch.deferno.core.data.settings

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
 * upserts the singleton row. A failed pull (the remote returns `null` offline) skips the upsert,
 * leaving the cached settings intact (offline-first).
 */
class OfflineSettingsRepository(
    private val localStore: SettingsLocalStore,
    private val remoteSource: SettingsRemoteSource,
) : SettingsRepository {

    override fun observeSettings(): Flow<UserSettings> =
        localStore.observeSettings().map { it ?: UserSettings.Default }

    override suspend fun refresh() {
        val remote = remoteSource.fetchSettings() ?: return
        localStore.upsert(remote)
    }
}
