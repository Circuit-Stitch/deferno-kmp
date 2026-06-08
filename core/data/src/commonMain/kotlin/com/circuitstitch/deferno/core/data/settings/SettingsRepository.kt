package com.circuitstitch.deferno.core.data.settings

import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * The offline-first observable read seam for the user's settings (ADR-0001, #72). The UI observes
 * [observeSettings] — a local-cache `Flow` only, never the network — so settings (and the theme it
 * drives) are observable from the start. [refresh] pulls a remote snapshot and reconciles it into
 * the local cache; the observed `Flow` re-emits.
 *
 * Unlike Task/Plan, the settings stream is **never empty**: [observeSettings] seeds a default
 * ([UserSettings.Default]) until the first refresh lands, so the theme always has a value (including
 * in the no-account / Auth-shell state).
 */
interface SettingsRepository {

    /** The current settings as a `Flow`, seeded with the default until the cache is populated. */
    fun observeSettings(): Flow<UserSettings>

    /** Pull the remote snapshot and reconcile it into the local cache (a no-op on a failed pull). */
    suspend fun refresh()
}
