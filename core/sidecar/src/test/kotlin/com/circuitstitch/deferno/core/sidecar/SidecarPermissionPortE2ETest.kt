package com.circuitstitch.deferno.core.sidecar

import app.cash.turbine.test
import com.circuitstitch.deferno.core.sidecar.SidecarTestHarness.Companion.DEADLINE
import com.circuitstitch.deferno.core.sidecar.SidecarTestHarness.Companion.posixSupported
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The #120 permission state machine end-to-end on the Linux fast path: the real
 * [DefaultSidecarPermissionPort] over the real [DefaultSidecarClient] + AF_UNIX socket against the
 * [StubHelper] — the discrete **query → not-determined → request → granted/denied** flow the New
 * surface's Dictation UX rides (the canned counterpart of the real Helper's TCC prompt), plus the
 * settled state landing as a [SidecarTopics.PermissionChanged] push.
 */
class SidecarPermissionPortE2ETest {

    private val harness = SidecarTestHarness()

    @AfterTest
    fun tearDown() = harness.close()

    @Test
    fun notDeterminedSettlesGrantedThroughARealRequest() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also {
            it.permissionStatus = PermissionStatusValue.NOT_DETERMINED
            it.requestOutcome = PermissionStatusValue.GRANTED
        }
        val port = DefaultSidecarPermissionPort(harness.client(stub.path))

        assertEquals(PermissionStatusValue.NOT_DETERMINED, port.status(SidecarPermissionCapabilities.Microphone))
        assertEquals(PermissionStatusValue.GRANTED, port.request(SidecarPermissionCapabilities.Microphone))
        // The grant settled: introspection now reports it (and still never prompts).
        assertEquals(PermissionStatusValue.GRANTED, port.status(SidecarPermissionCapabilities.Microphone))
    }

    @Test
    fun notDeterminedSettlesDeniedAndStaysDeniedOnReRequest() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also {
            it.permissionStatus = PermissionStatusValue.NOT_DETERMINED
            it.requestOutcome = PermissionStatusValue.DENIED
        }
        val port = DefaultSidecarPermissionPort(harness.client(stub.path))

        assertEquals(PermissionStatusValue.DENIED, port.request(SidecarPermissionCapabilities.Speech))
        // A denial is terminal: re-requesting reports it without re-prompting (the contract).
        assertEquals(PermissionStatusValue.DENIED, port.request(SidecarPermissionCapabilities.Speech))
    }

    @Test
    fun theSettledStateLandsAsAPermissionChangedPush() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also {
            it.permissionStatus = PermissionStatusValue.NOT_DETERMINED
            it.requestOutcome = PermissionStatusValue.DENIED
        }
        val client = harness.client(stub.path)
        val port = DefaultSidecarPermissionPort(client)
        client.connect()

        // Collect BEFORE requesting (the stub pushes right after answering).
        port.changes().test(timeout = DEADLINE) {
            port.request(SidecarPermissionCapabilities.Microphone)
            assertEquals(
                PermissionStatusWire(SidecarPermissionCapabilities.Microphone, PermissionStatusValue.DENIED),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
