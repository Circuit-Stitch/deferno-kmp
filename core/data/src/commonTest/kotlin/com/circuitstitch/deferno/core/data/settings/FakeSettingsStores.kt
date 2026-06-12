package com.circuitstitch.deferno.core.data.settings

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [SettingsLocalStore] for the repository/writer unit tests (#72, ADR-0006 JVM-fast path).
 * Backed by a [MutableStateFlow], so [observeSettings] is a real, re-emitting `Flow` (Turbine-
 * observable) without a database. [SqlDelightSettingsLocalStore] proves the SQL translation
 * separately, so neither test carries both concerns.
 */
class FakeSettingsLocalStore(initial: UserSettings? = null) : SettingsLocalStore {

    private val row = MutableStateFlow(initial)

    /** Direct read of the cached row for assertions. */
    val current: UserSettings? get() = row.value

    override fun observeSettings(): Flow<UserSettings?> = row

    override suspend fun currentSettings(): UserSettings? = row.value

    override suspend fun upsert(settings: UserSettings) {
        row.value = settings
    }
}

/**
 * Programmable [SettingsRemoteSource] for the repository tests (#72). [next] is the snapshot a
 * [fetchSettings] returns; setting it `null` simulates an offline / failed pull.
 */
class FakeSettingsRemoteSource(var next: UserSettings? = null) : SettingsRemoteSource {
    var fetchCount = 0
        private set

    override suspend fun fetchSettings(): RemoteSnapshot<UserSettings> {
        fetchCount++
        return next?.let { RemoteSnapshot.Available(it) } ?: RemoteSnapshot.Unavailable
    }
}
