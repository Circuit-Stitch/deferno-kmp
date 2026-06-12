package com.circuitstitch.deferno.core.data.settings

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.UserSettings

/**
 * The remote read port for the user's settings (#72, ADR-0001). Mirrors `PlanRemoteSource` /
 * `TaskRemoteSource`: a thin seam over `GET /auth/me/settings` so the offline-first repository can
 * pull a snapshot to reconcile into the local cache, while staying unit-testable against a fake on
 * the ADR-0006 JVM-fast path. AppScope (the shared client follows the Active Account, ADR-0014).
 */
interface SettingsRemoteSource {

    /**
     * Pulls the current settings as [RemoteSnapshot.Available], or [RemoteSnapshot.Unavailable] on any
     * failure (offline-first, ADR-0001) — a failed pull leaves the cached settings untouched, so the UI
     * keeps observing the last-known values.
     */
    suspend fun fetchSettings(): RemoteSnapshot<UserSettings>
}
