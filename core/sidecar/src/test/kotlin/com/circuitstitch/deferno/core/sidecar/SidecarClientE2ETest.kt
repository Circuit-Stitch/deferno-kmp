package com.circuitstitch.deferno.core.sidecar

import app.cash.turbine.test
import com.circuitstitch.deferno.core.sidecar.SidecarTestHarness.Companion.DEADLINE
import com.circuitstitch.deferno.core.sidecar.SidecarTestHarness.Companion.posixSupported
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The headline tracer-bullet test (#118): the real [DefaultSidecarClient] over a real AF_UNIX
 * [UnixSocketTransport] talking to the [StubHelper] over a real socket — proving the entire JVM client
 * path (connect, peer-auth handshake, request/response, server stream, push, cancel, graceful
 * degradation) **end-to-end on the Linux fast path with no Mac** (ADR-0024).
 *
 * Every await that crosses the real socket — Turbine `.test(...)` blocks included — carries the shared
 * [SidecarTestHarness.DEADLINE] last-resort bound: Turbine's default 3s `awaitItem` flaked on a starved
 * CI runner exactly like the 2s `withTimeout`s before it (#161).
 */
class SidecarClientE2ETest {

    private val harness = SidecarTestHarness()

    @AfterTest
    fun tearDown() = harness.close()

    @Test
    fun connectsAuthenticatesAndSurfacesCapabilities() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val client = harness.client(stub.path)

        client.connect()

        assertEquals(StubHelper.DEFAULT_CAPABILITIES, client.capabilities())
        assertIs<SidecarConnectionState.Ready>(client.state.value)
    }

    @Test
    fun completesARequestResponseRoundTrip() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also { it.permissionStatus = PermissionStatusValue.GRANTED }
        val client = harness.client(stub.path)
        client.connect()

        val result = checkNotNull(client.request(SidecarMethods.QueryPermission))
        val status = SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), result)

        assertEquals("speech", status.capability)
        assertEquals(PermissionStatusValue.GRANTED, status.status)
    }

    @Test
    fun consumesAServerStream() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val client = harness.client(stub.path)
        client.connect()

        client.openStream(SidecarMethods.SubscribeTranscript).test(timeout = DEADLINE) {
            assertEquals(TranscriptWire.Partial("hel"), transcript(awaitItem()))
            assertEquals(TranscriptWire.Partial("hello wor"), transcript(awaitItem()))
            assertEquals(TranscriptWire.Final("hello world"), transcript(awaitItem()))
            awaitComplete()
        }
    }

    @Test
    fun receivesAnUnsolicitedPush() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also { it.permissionStatus = PermissionStatusValue.DENIED }
        val client = harness.client(stub.path)
        client.connect()

        // Collect pushes BEFORE triggering the stub to push (the stub pushes after answering the query).
        client.pushes.test(timeout = DEADLINE) {
            client.request(SidecarMethods.QueryPermission)
            val push = awaitItem()
            assertEquals(SidecarTopics.PermissionChanged, push.topic)
            val status = SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), push.payload)
            assertEquals(PermissionStatusValue.DENIED, status.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cancellingTheStreamTellsTheHelperToStop() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val client = harness.client(stub.path)
        client.connect()

        client.openStream(SidecarMethods.SubscribeTranscript).test(timeout = DEADLINE) {
            awaitItem() // first partial, then we abandon the stream
            // Ignore-remaining (not plain cancel()): the stub keeps streaming until the Cancel
            // reaches it, so the second partial can already sit in Turbine's buffer — a legitimate
            // in-flight event, not a leak; plain cancel() flakes on ensureAllEventsConsumed.
            cancelAndIgnoreRemainingEvents()
        }

        val cancelledId = withTimeout(DEADLINE) { stub.awaitCancel() }
        assertTrue(cancelledId > 0, "expected the Helper to receive a Cancel for the open stream")
    }

    @Test
    fun reDialsAfterTheConnectionDrops() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val path = harness.socketDir().resolve("h.sock")
        val client = harness.client(path)

        val stub1 = harness.startStub(path = path)
        client.connect()
        assertEquals(SidecarConnectionState.Ready(StubHelper.DEFAULT_CAPABILITIES), client.state.value)

        // Drop the Helper; the client's reader sees EOF and transitions to Disconnected.
        stub1.close()
        withTimeout(DEADLINE) { client.state.first { it is SidecarConnectionState.Disconnected } }

        // A fresh Helper binds the same path; the next request re-dials transparently.
        harness.startStub(path = path)
        val result = checkNotNull(client.request(SidecarMethods.QueryPermission))
        assertEquals("speech", SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), result).capability)
    }

    // --- the Notification port (#123) --------------------------------------------------------------

    @Test
    fun postsANotificationWhenGranted() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also { it.permissionStatus = PermissionStatusValue.GRANTED }
        val client = harness.client(stub.path)
        client.connect()

        val port = SidecarNotificationPort(client)
        assertTrue(port.isAvailable())
        port.post(PostNotificationWire(title = "Deferno", body = "\"Pack for the trip\" is due soon"))

        val posted = withTimeout(DEADLINE) { stub.awaitNotification() }
        assertEquals(PostNotificationWire("Deferno", "\"Pack for the trip\" is due soon"), posted)
    }

    @Test
    fun refusesToPostWithoutAGrant() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also { it.permissionStatus = PermissionStatusValue.DENIED }
        val client = harness.client(stub.path)
        client.connect()

        val failure = assertFailsWith<SidecarRequestException> {
            SidecarNotificationPort(client).post(PostNotificationWire(title = "Deferno"))
        }
        assertEquals(SidecarErrorCode.UNAVAILABLE, failure.error.code)
    }

    @Test
    fun refusesANotificationWithAnEmptyTitle() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val client = harness.client(stub.path)
        client.connect()

        val failure = assertFailsWith<SidecarRequestException> {
            SidecarNotificationPort(client).post(PostNotificationWire(title = ""))
        }
        assertEquals(SidecarErrorCode.INVALID_PARAMS, failure.error.code)
    }

    @Test
    fun introspectsTheNotificationPermission() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also { it.permissionStatus = PermissionStatusValue.NOT_DETERMINED }
        val client = harness.client(stub.path)
        client.connect()

        assertEquals(PermissionStatusValue.NOT_DETERMINED, SidecarNotificationPort(client).permission())
    }

    @Test
    fun observesNotificationPermissionChanges() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also { it.permissionStatus = PermissionStatusValue.DENIED }
        val client = harness.client(stub.path)
        client.connect()

        val port = SidecarNotificationPort(client)
        // The stub pushes permissionChanged (echoing the queried capability) after answering a query —
        // a speech-capability change must NOT reach the notifications filter, the notifications one must.
        port.permissionChanges.test(timeout = DEADLINE) {
            client.request(SidecarMethods.QueryPermission) // capability defaults to speech → filtered out
            port.permission()
            assertEquals(PermissionStatusValue.DENIED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- the status-item + hotkey ports (#125) -----------------------------------------------------

    @Test
    fun showsTheStatusItemAndObservesItsClicks() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val client = harness.client(stub.path)
        client.connect()

        val port = SidecarStatusItemPort(client)
        assertTrue(port.isAvailable())
        port.clicks.test(timeout = DEADLINE) {
            port.setVisible(true) // the stub acks, then pushes one canned click
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        port.setVisible(false) // hide acks without a click push
    }

    @Test
    fun registersAHotkeyAndObservesItsFires() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val client = harness.client(stub.path)
        client.connect()

        val port = SidecarHotkeyPort(client)
        assertTrue(port.isAvailable())
        port.fires.test(timeout = DEADLINE) {
            port.register(7, "d", setOf(HotkeyModifier.COMMAND, HotkeyModifier.SHIFT))
            assertEquals(7L, awaitItem()) // the stub acks, then pushes one canned fire
            cancelAndIgnoreRemainingEvents()
        }

        port.unregister(7)
        assertEquals(7L, withTimeout(DEADLINE) { stub.awaitUnregister() })
    }

    @Test
    fun refusesAnUnknownHotkeyKey() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val client = harness.client(stub.path)
        client.connect()

        val failure = assertFailsWith<SidecarRequestException> {
            SidecarHotkeyPort(client).register(1, "münzwurf", setOf(HotkeyModifier.COMMAND))
        }
        assertEquals(SidecarErrorCode.INVALID_PARAMS, failure.error.code)
    }

    @Test
    fun refusesAHotkeyWithoutModifiers() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val client = harness.client(stub.path)
        client.connect()

        val failure = assertFailsWith<SidecarRequestException> {
            SidecarHotkeyPort(client).register(1, "d", emptySet())
        }
        assertEquals(SidecarErrorCode.INVALID_PARAMS, failure.error.code)
    }

    @Test
    fun rejectsAnInvalidToken() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val client = harness.client(stub.path, token = "wrong-token")

        assertFailsWith<SidecarSecurityException> { client.connect() }
        assertIs<SidecarConnectionState.Disconnected>(client.state.value)
    }

    @Test
    fun degradesGracefullyWhenNoHelperIsBound() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val absent = harness.socketDir().resolve("not-bound.sock")
        val client = harness.client(absent)

        assertFailsWith<SidecarUnavailableException> { client.connect() }
    }

    @Test
    fun refusesAnInsecureSocketPath() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        // Make the bound socket group/other-readable — the client-half peer-trust must refuse it.
        Files.setPosixFilePermissions(stub.path, PosixFilePermissions.fromString("rw-r--r--"))
        val client = harness.client(stub.path)

        assertFailsWith<SidecarSecurityException> { client.connect() }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun transcript(event: kotlinx.serialization.json.JsonElement): TranscriptWire =
        SidecarJson.decodeFromJsonElement(TranscriptWire.serializer(), event)
}
