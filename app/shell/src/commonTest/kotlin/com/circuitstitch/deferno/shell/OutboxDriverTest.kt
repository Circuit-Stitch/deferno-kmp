package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.data.connectivity.AssumeOnlineConnectivity
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The [OutboxDriver] contract (#143/#158), tested directly on the virtual clock — extracted from
 * `RootComponent` so the subtle flush timing has one home: flush-before-reconcile ordering, the
 * periodic cadence, the offline→online edge, and the known-offline skip. `RootComponentTest` keeps
 * only the lifecycle **wiring** (the shell drives on Main, stops on Auth, re-points on switch), so the
 * shell test no longer carries this timing logic. The driver runs on [TestScope.backgroundScope], which
 * the scheduler auto-cancels at the end of each test — no `stop()` needed for cleanup (the [stop] test
 * exercises it explicitly).
 */
@OptIn(ExperimentalCoroutinesApi::class) // backgroundScope / advanceTimeBy / runCurrent drive the virtual scheduler.
class OutboxDriverTest {

    private val t0 = Instant.parse("2026-06-06T08:00:00Z")

    private fun TestScope.driver(connectivity: Connectivity = AssumeOnlineConnectivity()) =
        OutboxDriver(backgroundScope, connectivity, { t0 }, 30.seconds)

    /** A [SettingsRepository] that runs [onRefresh] on each [refresh] (for the ordering assertions). */
    private fun recordingSettings(onRefresh: () -> Unit) = object : SettingsRepository {
        override fun observeSettings(): Flow<UserSettings> = MutableStateFlow(UserSettings.Default)
        override suspend fun refresh() = onRefresh()
    }

    @Test
    fun driveFlushesTheOutboxBeforeReconcilingSettings() = runTest {
        // The #143 order: the queued offline writes reach the server FIRST, so the settings pull that
        // follows can't fetch a snapshot that predates them (the cold-start theme revert).
        val events = mutableListOf<String>()
        val session = FakeAccountSession(
            settingsRepository = recordingSettings { events += "settings.refresh" },
            onFlush = { events += "flush" },
        )

        driver().drive(session)
        runCurrent()

        assertEquals(listOf("flush", "settings.refresh"), events)
    }

    @Test
    fun keepsFlushingPeriodicallyWhileDriven() = runTest {
        // Writes made DURING a session (not just at activation) must sync without waiting for the next
        // cold start — the periodic re-flush picks them up (#143).
        val session = FakeAccountSession()
        driver().drive(session)
        runCurrent()
        assertEquals(1, session.flushes.size, "the activation flush")

        advanceTimeBy(31.seconds)
        assertEquals(2, session.flushes.size, "one periodic re-flush per period")

        advanceTimeBy(30.seconds)
        assertEquals(3, session.flushes.size)
    }

    @Test
    fun stopHaltsTheFlushLoop() = runTest {
        val session = FakeAccountSession()
        val driver = driver()
        driver.drive(session)
        runCurrent()
        assertEquals(1, session.flushes.size)

        driver.stop()
        advanceTimeBy(120.seconds)

        assertEquals(1, session.flushes.size, "no further flushes after stop()")
    }

    @Test
    fun driveRePointsAtTheNewSession_cancellingThePrior() = runTest {
        val work = FakeAccountSession()
        val personal = FakeAccountSession()
        val driver = driver()

        driver.drive(work)
        runCurrent()
        assertEquals(1, work.flushes.size)

        driver.drive(personal)
        runCurrent()
        advanceTimeBy(31.seconds)

        // The prior session's loop is cancelled (account isolation); only the new one flushes on
        // activation and keeps the periodic cadence.
        assertEquals(1, work.flushes.size)
        assertEquals(2, personal.flushes.size)
    }

    @Test
    fun reconnectFlushesImmediately_withoutWaitingOutTheTick() = runTest {
        val session = FakeAccountSession()
        val connectivity = FakeConnectivity(online = true)
        driver(connectivity).drive(session)
        runCurrent()
        assertEquals(1, session.flushes.size, "the activation flush")

        connectivity.online.value = false
        advanceTimeBy(5.seconds) // well inside the tick — recovery must not be bounded by it
        connectivity.online.value = true
        runCurrent()

        assertEquals(2, session.flushes.size, "the reconnect edge flushes immediately, not at the next tick")
    }

    @Test
    fun whileKnownOffline_periodicPassesAreSkipped_soQueuedWritesCannotBurnAttempts() = runTest {
        val session = FakeAccountSession()
        val connectivity = FakeConnectivity(online = true)
        driver(connectivity).drive(session)
        runCurrent()
        assertEquals(1, session.flushes.size)

        connectivity.online.value = false
        advanceTimeBy(95.seconds) // three ticks' worth of flight mode

        // No pass runs while known-offline: a long offline stretch must not walk the queued writes into
        // the replay engine's give-up policy (#158) — maxAttempts measures real server failures.
        assertEquals(1, session.flushes.size, "offline ticks burn no attempts")

        connectivity.online.value = true
        runCurrent()
        assertEquals(2, session.flushes.size, "the reconnect edge")

        advanceTimeBy(31.seconds)
        assertEquals(3, session.flushes.size, "the periodic cadence resumes once online")
    }

    @Test
    fun aFlushFailure_doesNotCrashTheDriver_andItKeepsFlushing() = runTest {
        // A flush throw (e.g. a DB open failure, like the iOS schema-downgrade ISE that surfaced this)
        // must NOT escape the driver coroutine — on Kotlin/Native an uncaught exception on the Main
        // dispatcher aborts the app. The periodic loop has to survive it and keep retrying.
        var failFirstFlush = true
        val session = FakeAccountSession(onFlush = {
            if (failFirstFlush) { failFirstFlush = false; throw IllegalStateException("boom (e.g. DB downgrade)") }
        })

        driver().drive(session)
        runCurrent()
        assertEquals(1, session.flushes.size, "the activation flush was attempted (and failed)")

        advanceTimeBy(31.seconds)
        assertEquals(2, session.flushes.size, "the driver survived the failure and re-flushed")
    }

    @Test
    fun driveWhileOffline_skipsTheActivationFlush_untilTheReconnectEdge() = runTest {
        val events = mutableListOf<String>()
        val session = FakeAccountSession(
            settingsRepository = recordingSettings { events += "settings.refresh" },
            onFlush = { events += "flush" },
        )
        val connectivity = FakeConnectivity(online = false)
        driver(connectivity).drive(session)
        runCurrent()

        // Known-offline at activation: the flush is skipped (it could only burn an attempt); the
        // settings refresh still runs (and fails harmlessly offline, unchanged from #143).
        assertEquals(listOf("settings.refresh"), events)

        connectivity.online.value = true
        runCurrent()

        // The reconnect edge re-runs the sequence — flush FIRST, then the settings reconcile.
        assertEquals(listOf("settings.refresh", "flush", "settings.refresh"), events)
    }
}

/** A [Connectivity] whose online/offline state the test drives (`online.value = …`) (#158). */
private class FakeConnectivity(online: Boolean) : Connectivity {
    override val online = MutableStateFlow(online)
}
