package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.data.settings.FakeSettingsLocalStore
import com.circuitstitch.deferno.core.data.settings.FakeSettingsRemoteSource
import com.circuitstitch.deferno.core.data.settings.OfflineSettingsRepository
import com.circuitstitch.deferno.core.data.settings.OutboxSettingsWriter
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The #143 regression, end to end: a theme change made while its `PATCH` is still queued must survive
 * a cold-start reconcile (the refresh that used to clobber it), and converge on server truth once the
 * outbox flushes. Wires the real engine — writer (optimistic apply + enqueue) → outbox → processor →
 * hardened settings reconcile — to the in-memory fakes and a scriptable "server", mirroring
 * [OfflineToOnlineTest] on the ADR-0006 JVM-fast path.
 */
class SettingsOfflineToOnlineTest {

    private val t0 = Instant.parse("2026-06-07T12:00:00Z")
    private val chosen = UserSettings(themeFamily = ThemeFamily.Mono, themeMode = ThemeMode.Dark)
    private val stale = UserSettings.Default.copy(themeMode = ThemeMode.Light)

    @Test
    fun coldStartReconcileCannotRevertAQueuedThemeChange_andFlushConverges() = runTest {
        val local = FakeSettingsLocalStore(initial = stale)
        val remote = FakeSettingsRemoteSource(next = stale) // server truth: still the old theme
        val outbox = FakeOutboxStore()
        val repository = OfflineSettingsRepository(local, remote, outbox)
        val writer = OutboxSettingsWriter(local, outbox, now = { t0 })

        var online = false
        val sender = object : OutboxRequestSender {
            override suspend fun send(request: OutboxRequest): SendOutcome {
                if (!online) return SendOutcome.Retryable
                remote.next = chosen // the server applied the PATCH — its snapshot now carries it
                return SendOutcome.Success
            }
        }
        val processor = OutboxProcessor(outbox, sender, reconcile = { repository.refresh() })

        // The user picks a theme: optimistic local apply + queued PATCH (live Appearance).
        writer.setTheme(ThemeFamily.Mono, ThemeMode.Dark)
        assertEquals(chosen, local.current)
        assertEquals(1L, outbox.count())

        // "Cold start" while the PATCH is still queued: the reconcile pulls the STALE server snapshot.
        // The pending mutation is newer (LWW) — the choice must stand. This is the #143 revert.
        repository.refresh()
        assertEquals(chosen, local.current, "an un-synced optimistic change survives the reconcile")

        // A flush while offline makes no progress; the optimism (and the queue) stay intact.
        processor.flush(t0)
        assertEquals(chosen, local.current)
        assertEquals(1L, outbox.count())

        // Connectivity returns: the flush drains the PATCH, then its reconcile pulls server truth —
        // which now includes the change — and the row converges.
        online = true
        val result = processor.flush(t0 + 2.seconds)
        assertEquals(1, result.succeeded)
        assertEquals(0L, outbox.count())
        assertEquals(chosen, local.current)

        // With the queue empty, a later clean refresh upserts the (now-current) snapshot as usual.
        repository.refresh()
        assertEquals(chosen, local.current)
    }
}
