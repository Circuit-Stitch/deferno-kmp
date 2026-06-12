package com.circuitstitch.deferno.core.sidecar

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The [DefaultSidecarPermissionPort] contract (#120, ADR-0024): [SidecarPermissionPort.status] /
 * [SidecarPermissionPort.request] **degrade to [PermissionStatusValue.UNKNOWN], never throw** — a
 * permission check can never crash a consumer's availability path — and [SidecarPermissionPort.changes]
 * is a tolerant reader (ADR-0005): an unreadable push is dropped, never thrown at a collector.
 */
class SidecarPermissionPortTest {

    // --- status / request: the two unary legs ---------------------------------------------------------

    @Test
    fun statusQueriesTheNamedCapabilityWithoutPrompting() = runTest {
        val client = FakeSidecarClient(permissionStatus = PermissionStatusValue.NOT_DETERMINED)

        val status = DefaultSidecarPermissionPort(client).status(SidecarPermissionCapabilities.Microphone)

        assertEquals(PermissionStatusValue.NOT_DETERMINED, status)
        assertEquals(SidecarMethods.QueryPermission, client.lastRequestMethod)
        assertEquals(
            QueryPermissionWire(SidecarPermissionCapabilities.Microphone),
            SidecarJson.decodeFromJsonElement(QueryPermissionWire.serializer(), client.lastRequestParams!!),
        )
    }

    @Test
    fun requestSendsTheRequestPermissionMethodForTheNamedCapability() = runTest {
        val client = FakeSidecarClient(permissionStatus = PermissionStatusValue.GRANTED)

        val status = DefaultSidecarPermissionPort(client).request(SidecarPermissionCapabilities.Speech)

        assertEquals(PermissionStatusValue.GRANTED, status)
        assertEquals(SidecarMethods.RequestPermission, client.lastRequestMethod)
        assertEquals(
            RequestPermissionWire(SidecarPermissionCapabilities.Speech),
            SidecarJson.decodeFromJsonElement(RequestPermissionWire.serializer(), client.lastRequestParams!!),
        )
    }

    @Test
    fun reportsTheSettledDenialFromARequest() = runTest {
        val client = FakeSidecarClient(permissionStatus = PermissionStatusValue.DENIED)
        assertEquals(
            PermissionStatusValue.DENIED,
            DefaultSidecarPermissionPort(client).request(SidecarPermissionCapabilities.Microphone),
        )
    }

    // --- degrade, never throw -------------------------------------------------------------------------

    @Test
    fun degradesToUnknownWhenNoHelperIsBound() = runTest {
        val client = FakeSidecarClient(connectFailure = SidecarUnavailableException("nothing listening"))
        val port = DefaultSidecarPermissionPort(client)
        assertEquals(PermissionStatusValue.UNKNOWN, port.status(SidecarPermissionCapabilities.Microphone))
        assertEquals(PermissionStatusValue.UNKNOWN, port.request(SidecarPermissionCapabilities.Microphone))
    }

    @Test
    fun degradesToUnknownWhenTheHelperDoesNotBrokerPermissions() = runTest {
        val client = FakeSidecarClient(advertisedCapabilities = setOf(SidecarCapabilities.SpeechTranscribe))
        val port = DefaultSidecarPermissionPort(client)
        assertEquals(PermissionStatusValue.UNKNOWN, port.status(SidecarPermissionCapabilities.Speech))
        assertEquals(0, client.requests) // never even called — the capability isn't advertised
    }

    @Test
    fun degradesToUnknownWhenTheCallFails() = runTest {
        val client = FakeSidecarClient(
            requestFailure = SidecarRequestException(SidecarError(SidecarErrorCode.INTERNAL, "boom")),
        )
        val port = DefaultSidecarPermissionPort(client)
        assertEquals(PermissionStatusValue.UNKNOWN, port.status(SidecarPermissionCapabilities.Microphone))
        assertEquals(PermissionStatusValue.UNKNOWN, port.request(SidecarPermissionCapabilities.Microphone))
    }

    @Test
    fun degradesToUnknownOnAnUnreadableOrMissingReply() = runTest {
        val client = FakeSidecarClient()
        val port = DefaultSidecarPermissionPort(client)

        client.requestAnswer = { buildJsonObject { put("garbage", true) } }
        assertEquals(PermissionStatusValue.UNKNOWN, port.status(SidecarPermissionCapabilities.Microphone))

        client.requestAnswer = { null } // a no-content ack — nothing to read
        assertEquals(PermissionStatusValue.UNKNOWN, port.request(SidecarPermissionCapabilities.Microphone))
    }

    // --- changes: the tolerant push stream ------------------------------------------------------------

    @Test
    fun mapsPermissionChangedPushesAndIgnoresOtherTopics() = runTest {
        val client = FakeSidecarClient()
        val changed = PermissionStatusWire(SidecarPermissionCapabilities.Microphone, PermissionStatusValue.DENIED)

        DefaultSidecarPermissionPort(client).changes().test {
            client.push(SidecarPush(SidecarTopics.HotkeyFired, buildJsonObject { put("id", 1) }))
            client.push(
                SidecarPush(
                    SidecarTopics.PermissionChanged,
                    SidecarJson.encodeToJsonElement(PermissionStatusWire.serializer(), changed),
                ),
            )
            assertEquals(changed, awaitItem())
        }
    }

    @Test
    fun dropsAnUnreadablePermissionChangedPayload() = runTest {
        val client = FakeSidecarClient()
        val after = PermissionStatusWire(SidecarPermissionCapabilities.Speech, PermissionStatusValue.GRANTED)

        DefaultSidecarPermissionPort(client).changes().test {
            client.push(SidecarPush(SidecarTopics.PermissionChanged, JsonPrimitive("garbage")))
            client.push(
                SidecarPush(
                    SidecarTopics.PermissionChanged,
                    SidecarJson.encodeToJsonElement(PermissionStatusWire.serializer(), after),
                ),
            )
            assertEquals(after, awaitItem()) // the unreadable one was dropped, the stream survived
        }
    }

    // --- the deep-link leg ------------------------------------------------------------------------------

    @Test
    fun deepLinksTheMacPrivacyPanePerCapability() {
        assertEquals(
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone",
            SidecarPermissionSettingsLinks.forCapability(SidecarPermissionCapabilities.Microphone, osName = "Mac OS X"),
        )
        assertEquals(
            "x-apple.systempreferences:com.apple.preference.security?Privacy_SpeechRecognition",
            SidecarPermissionSettingsLinks.forCapability(SidecarPermissionCapabilities.Speech, osName = "Mac OS X"),
        )
    }

    @Test
    fun deepLinksNothingOffMacOsOrForAnUnmappedCapability() {
        assertNull(SidecarPermissionSettingsLinks.forCapability(SidecarPermissionCapabilities.Microphone, osName = "Linux"))
        assertNull(SidecarPermissionSettingsLinks.forCapability(SidecarPermissionCapabilities.Notifications, osName = "Mac OS X"))
    }
}
