package com.circuitstitch.deferno.core.data.connectivity

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The connectivity seam (#158): the assume-online fallback (the pre-#158 posture) and the
 * best-effort poller behind the desktop binding — its probe cadence, its offline→online edge, and
 * its fail-open behaviour on a throwing probe.
 */
class ConnectivityTest {

    @Test
    fun assumeOnline_isAlwaysOnline_theNonBreakingFallback() = runTest {
        val connectivity = AssumeOnlineConnectivity()

        assertTrue(connectivity.online.value)
        assertTrue(connectivity.isOnline(), "the create-gate probe answers from the same signal")
    }

    @Test
    fun polling_mirrorsTheProbe_andEmitsTheReconnectEdge() = runTest {
        var reachable = false
        val connectivity = PollingConnectivity(
            probe = { reachable },
            period = 15.seconds,
            scope = backgroundScope,
        )

        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connectivity.online.collect { seen += it }
        }
        runCurrent()
        assertFalse(connectivity.online.value, "the first probe lands as soon as it is observed")

        reachable = true
        advanceTimeBy(16.seconds)

        assertTrue(connectivity.online.value)
        // Distinct-until-changed: exactly the initial assume-online, the offline sample, the edge.
        assertEquals(listOf(true, false, true), seen)
        assertTrue(connectivity.isOnline(), "the create-gate probe answers from the same signal")
    }

    @Test
    fun polling_aThrowingProbeFailsOpen_neverFalselyOffline() = runTest {
        val connectivity = PollingConnectivity(
            probe = { error("no reachability check available") },
            period = 15.seconds,
            scope = backgroundScope,
        )

        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connectivity.online.collect { seen += it }
        }
        advanceTimeBy(31.seconds) // several probe rounds, all throwing — and none crash the poller

        assertEquals(listOf(true), seen, "a probe failure must count as online (fail open), never a false offline")
    }
}
