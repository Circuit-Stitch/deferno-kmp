package com.circuitstitch.deferno.core.data.settings

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Settings write path (#72, ADR-0001): [OutboxSettingsWriter] applies each intent optimistically
 * to the local cache (so the theme `Flow` — and the live app theme — reflects it instantly) and
 * enqueues its idempotent `PATCH /auth/me/settings` for replay. This is the **Appearance round-trip**
 * the acceptance criteria require, asserted at the data layer. Mirrors
 * [com.circuitstitch.deferno.core.data.task.OutboxTaskWriterTest]. Run against the in-memory fakes.
 */
class OutboxSettingsWriterTest {

    private val now = Instant.parse("2026-06-07T12:00:00Z")

    private fun writer(local: FakeSettingsLocalStore, outbox: FakeOutboxStore) =
        OutboxSettingsWriter(local, outbox, now = { now })

    @Test
    fun setThemeAppliesOptimisticallyAndEnqueues() = runTest {
        val local = FakeSettingsLocalStore(initial = UserSettings.Default)
        val outbox = FakeOutboxStore()

        writer(local, outbox).setTheme(ThemeFamily.Mono, ThemeMode.Dark)

        // Optimistic local apply — visible immediately, before any network (this is what applies live).
        assertEquals(ThemeFamily.Mono, local.current?.themeFamily)
        assertEquals(ThemeMode.Dark, local.current?.themeMode)
        // Enqueued, ready to dispatch now.
        val entry = outbox.all.single()
        assertEquals("settings", entry.target)
        assertEquals(OutboxMethod.Patch, entry.request.method)
        assertEquals(listOf("auth", "me", "settings"), entry.request.path)
        assertEquals("""{"theme_family":"mono","theme_mode":"dark"}""", entry.request.body)
        assertEquals(now, entry.nextAttemptAt)
    }

    @Test
    fun toggleAppliesOptimisticallyAndEnqueues() = runTest {
        val local = FakeSettingsLocalStore(initial = UserSettings.Default)
        val outbox = FakeOutboxStore()

        writer(local, outbox).setDragAndDrop(true)

        assertEquals(true, local.current?.dragAndDropEnabled)
        assertEquals("""{"drag_and_drop_enabled":true}""", outbox.all.single().request.body)
    }

    @Test
    fun aWriteWithNoCachedRowStillAppliesAgainstTheDefaultAndEnqueues() = runTest {
        val local = FakeSettingsLocalStore(initial = null) // nothing cached yet
        val outbox = FakeOutboxStore()

        writer(local, outbox).setTracking(true)

        // The first change still applies cleanly against the seeded default.
        assertEquals(true, local.current?.trackingEnabled)
        assertEquals(1, outbox.all.size)
        assertEquals("""{"tracking_enabled":true}""", outbox.all.single().request.body)
    }

    @Test
    fun theOptimisticApplyReEmitsThroughTheObservedSettings() = runTest {
        val local = FakeSettingsLocalStore(initial = UserSettings.Default)
        val outbox = FakeOutboxStore()
        val writer = writer(local, outbox)

        local.observeSettings().test {
            assertEquals(ThemeFamily.Deferno, awaitItem()?.themeFamily)
            writer.setTheme(ThemeFamily.Mono, ThemeMode.Light)
            val updated = awaitItem()
            assertEquals(ThemeFamily.Mono, updated?.themeFamily)
            assertEquals(ThemeMode.Light, updated?.themeMode)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
