package com.circuitstitch.deferno.desktop.update

import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [UpdateManager]'s state machine (#103) over a [FakeUpdateBackend], so the Conveyor-driven update
 * flow is verified with no real package, network, or UI — the desktop counterpart of `DesktopNavKindTest`.
 * The fetch runs on the test scheduler ([EmptyCoroutineContext] keeps the manager's `withContext` on it),
 * so `advanceUntilIdle()` deterministically drives Checking → Available/UpToDate/Failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {

    @Test
    fun packagedApp_startsIdle() = runTest {
        val manager = manager(FakeUpdateBackend(currentVersion = "1.0.0"))
        assertEquals(UpdateState.Idle("1.0.0"), manager.state.value)
    }

    @Test
    fun unpackaged_startsUnsupported_notPackaged() = runTest {
        val manager = manager(FakeUpdateBackend(availability = UpdateAvailability.NOT_PACKAGED))
        assertEquals(
            UpdateState.Unsupported("1.0.0", UnsupportedReason.NOT_PACKAGED),
            manager.state.value,
        )
    }

    @Test
    fun linux_startsUnsupported_packageManager() = runTest {
        val manager = manager(FakeUpdateBackend(availability = UpdateAvailability.PACKAGE_MANAGER))
        assertEquals(
            UpdateState.Unsupported("1.0.0", UnsupportedReason.PACKAGE_MANAGER),
            manager.state.value,
        )
    }

    @Test
    fun check_newerVersion_becomesAvailable() = runTest {
        val backend = FakeUpdateBackend(currentVersion = "1.0.0").apply {
            result = CheckResult.Available("2.0.0")
        }
        val manager = manager(backend)

        manager.checkForUpdates()
        assertEquals(UpdateState.Checking("1.0.0"), manager.state.value)

        advanceUntilIdle()
        assertEquals(UpdateState.Available("1.0.0", "2.0.0"), manager.state.value)
    }

    @Test
    fun check_sameVersion_becomesUpToDate() = runTest {
        val backend = FakeUpdateBackend(currentVersion = "1.0.0").apply {
            result = CheckResult.UpToDate("1.0.0")
        }
        val manager = manager(backend)

        manager.checkForUpdates()
        advanceUntilIdle()
        assertEquals(UpdateState.UpToDate("1.0.0"), manager.state.value)
    }

    @Test
    fun check_failure_becomesFailed() = runTest {
        val backend = FakeUpdateBackend(currentVersion = "1.0.0").apply {
            error = IOException("offline")
        }
        val manager = manager(backend)

        manager.checkForUpdates()
        advanceUntilIdle()
        assertEquals(UpdateState.Failed("1.0.0"), manager.state.value)
    }

    @Test
    fun check_whenUnsupported_isNoOp() = runTest {
        val backend = FakeUpdateBackend(availability = UpdateAvailability.NOT_PACKAGED)
        val manager = manager(backend)

        manager.checkForUpdates()
        advanceUntilIdle()
        assertEquals(0, backend.checkCalls)
        assertTrue(manager.state.value is UpdateState.Unsupported)
    }

    @Test
    fun check_whileAlreadyChecking_doesNotStack() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeUpdateBackend(currentVersion = "1.0.0").apply {
            this.gate = gate
            result = CheckResult.Available("2.0.0")
        }
        val manager = manager(backend)

        manager.checkForUpdates() // enters Checking; the fetch awaits the gate
        manager.checkForUpdates() // ignored — a check is already in flight
        assertEquals(UpdateState.Checking("1.0.0"), manager.state.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, backend.checkCalls)
        assertEquals(UpdateState.Available("1.0.0", "2.0.0"), manager.state.value)
    }

    @Test
    fun install_fromAvailable_triggersUpdateAndBecomesInstalling() = runTest {
        val backend = FakeUpdateBackend(currentVersion = "1.0.0").apply {
            result = CheckResult.Available("2.0.0")
        }
        val manager = manager(backend)
        manager.checkForUpdates()
        advanceUntilIdle()

        manager.installUpdate()
        assertEquals(UpdateState.Installing("1.0.0", "2.0.0"), manager.state.value)
        assertEquals(1, backend.triggered)
    }

    @Test
    fun install_whenNotAvailable_isNoOp() = runTest {
        val backend = FakeUpdateBackend(currentVersion = "1.0.0")
        val manager = manager(backend) // starts Idle

        manager.installUpdate()
        assertEquals(UpdateState.Idle("1.0.0"), manager.state.value)
        assertEquals(0, backend.triggered)
    }

    /** Build a manager whose check coroutine runs on the test scheduler (so `advanceUntilIdle` drives it). */
    private fun kotlinx.coroutines.test.TestScope.manager(backend: UpdateBackend) =
        UpdateManager(backend = backend, scope = this, ioContext = EmptyCoroutineContext)

    private class FakeUpdateBackend(
        override val currentVersion: String = "1.0.0",
        private val availability: UpdateAvailability = UpdateAvailability.AVAILABLE,
    ) : UpdateBackend {
        var result: CheckResult = CheckResult.UpToDate(currentVersion)
        var error: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var checkCalls = 0
            private set
        var triggered = 0
            private set

        override fun availability() = availability

        override suspend fun checkForUpdate(): CheckResult {
            checkCalls++
            gate?.await()
            error?.let { throw it }
            return result
        }

        override fun triggerUpdate() {
            triggered++
        }
    }
}
