package com.circuitstitch.deferno.core.data.settings

import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.SetDoneVisibility
import com.circuitstitch.deferno.core.data.outbox.SetDragAndDrop
import com.circuitstitch.deferno.core.data.outbox.SetTheme
import com.circuitstitch.deferno.core.data.outbox.SetTracking
import com.circuitstitch.deferno.core.data.outbox.SettingsMutation
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The offline-first [SettingsWriter] (ADR-0001, #72): optimistic local apply + enqueue to the outbox.
 * Each write [submit]s a [SettingsMutation] — applying its pure optimistic transform to the cached
 * settings row and queueing its idempotent `PATCH /auth/me/settings` request — so the local source of
 * truth (and the theme `Flow` observing it) reflects the change **immediately** (live Appearance), and
 * the server catches up on the next outbox flush.
 *
 * The optimistic apply reads the current settings (or [UserSettings.Default] when nothing is cached
 * yet — the very first change still applies cleanly), transforms it, and upserts. A crash between the
 * upsert and the enqueue leaves an optimistic change with no queued request, which the next reconcile
 * reverts to server truth (LWW) — application-level atomicity, no cross-store transaction needed.
 *
 * [now] is injected (default the system clock) so the enqueue time is deterministic under test.
 */
class OutboxSettingsWriter(
    private val localStore: SettingsLocalStore,
    private val outbox: OutboxStore,
    private val now: () -> Instant = { Clock.System.now() },
) : SettingsWriter {

    override suspend fun setTheme(family: ThemeFamily, mode: ThemeMode) = submit(SetTheme(family, mode))

    override suspend fun setTracking(enabled: Boolean) = submit(SetTracking(enabled))

    override suspend fun setDragAndDrop(enabled: Boolean) = submit(SetDragAndDrop(enabled))

    override suspend fun setDoneVisibility(globalSeconds: Long?, dashboardSeconds: Long?) =
        submit(SetDoneVisibility(globalSeconds, dashboardSeconds))

    private suspend fun submit(mutation: SettingsMutation) {
        val current = localStore.currentSettings() ?: UserSettings.Default
        localStore.upsert(mutation.applyTo(current))
        outbox.enqueue(mutation.target, mutation.toRequest(), now())
    }
}
