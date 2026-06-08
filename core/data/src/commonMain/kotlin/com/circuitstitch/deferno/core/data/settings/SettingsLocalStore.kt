package com.circuitstitch.deferno.core.data.settings

import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * The local source-of-truth port for the single settings row (ADR-0001, #72). The repository talks to
 * *this*, never the network: the UI-facing read is the [observeSettings] DB `Flow`, and a refresh
 * reconciles through [upsert]. Extracting persistence behind a port keeps the offline-first behaviour
 * unit-testable against an in-memory fake on the ADR-0006 JVM-fast path, while
 * [SqlDelightSettingsLocalStore] proves the real SQLite path.
 */
interface SettingsLocalStore {

    /**
     * The cached settings, or `null` when nothing has been cached yet — observed as a `Flow`
     * (ADR-0001 observe-via-Flow-only); re-emits whenever the row changes. The repository seeds a
     * default until the first refresh lands.
     */
    fun observeSettings(): Flow<UserSettings?>

    /** The current cached settings (tombstone-free; a singleton), or `null` — the optimistic-apply read. */
    suspend fun currentSettings(): UserSettings?

    /** Inserts or replaces the singleton settings row. */
    suspend fun upsert(settings: UserSettings)
}
