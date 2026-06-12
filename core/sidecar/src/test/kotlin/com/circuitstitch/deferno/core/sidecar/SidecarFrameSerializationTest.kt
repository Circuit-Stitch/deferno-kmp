package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SidecarFrameSerializationTest {

    @Test
    fun tagsEachFrameWithItsTypeDiscriminator() {
        val json = encode(SidecarFrame.Request(id = 1, method = "queryPermission"))
        assertTrue(json.contains("\"type\":\"request\""), json)
    }

    @Test
    fun omitsNullAndDefaultFieldsForCompactFrames() {
        // explicitNulls=false → a null `params` is absent; encodeDefaults=false → an empty default is absent.
        val request = encode(SidecarFrame.Request(id = 1, method = "m", params = null))
        assertFalse(request.contains("params"), request)

        val welcome = encode(SidecarFrame.Welcome(protocolVersion = 1, capabilities = emptySet()))
        assertFalse(welcome.contains("capabilities"), welcome)
    }

    @Test
    fun toleratesUnknownKeysFromANewerHelper() {
        val frame = decode("""{"type":"welcome","protocolVersion":1,"futureField":{"x":true}}""")
        val welcome = assertIs<SidecarFrame.Welcome>(frame)
        assertEquals(1, welcome.protocolVersion)
    }

    @Test
    fun coercesAnUnknownErrorCodeToUnknown() {
        val frame = decode("""{"type":"failure","error":{"code":"teleport","message":"???"}}""")
        val failure = assertIs<SidecarFrame.Failure>(frame)
        assertEquals(SidecarErrorCode.UNKNOWN, failure.error.code)
    }

    @Test
    fun goldenFixturesDecodeToTheDocumentedFrames() {
        assertIs<SidecarFrame.Hello>(decode(fixture("hello.json")))
        assertIs<SidecarFrame.Welcome>(decode(fixture("welcome.json")))
        assertIs<SidecarFrame.Push>(decode(fixture("push-permission-changed.json")))
        assertIs<SidecarFrame.Failure>(decode(fixture("failure.json")))
    }

    @Test
    fun goldenResponsePayloadDecodesToThePermissionWire() {
        val response = assertIs<SidecarFrame.Response>(decode(fixture("query-permission-response.json")))
        val status = SidecarJson.decodeFromJsonElement(
            PermissionStatusWire.serializer(),
            checkNotNull(response.result),
        )
        assertEquals("speech", status.capability)
        assertEquals(PermissionStatusValue.GRANTED, status.status)
    }

    @Test
    fun goldenRequestPermissionRequestDecodesToTheRequestWire() {
        val request = assertIs<SidecarFrame.Request>(decode(fixture("request-permission-request.json")))
        assertEquals(SidecarMethods.RequestPermission, request.method)
        val wire = SidecarJson.decodeFromJsonElement(
            RequestPermissionWire.serializer(),
            checkNotNull(request.params),
        )
        assertEquals(RequestPermissionWire(SidecarPermissionCapabilities.Microphone), wire)
    }

    @Test
    fun goldenStreamPayloadDecodesToTheTranscriptWire() {
        val data = assertIs<SidecarFrame.StreamData>(decode(fixture("transcript-stream-data.json")))
        val event = SidecarJson.decodeFromJsonElement(TranscriptWire.serializer(), data.event)
        val partial = assertIs<TranscriptWire.Partial>(event)
        assertEquals("hello wor", partial.text)
    }

    @Test
    fun goldenPostNotificationRequestDecodesToTheNotificationWire() {
        val request = assertIs<SidecarFrame.Request>(decode(fixture("post-notification-request.json")))
        assertEquals(SidecarMethods.PostNotification, request.method)
        val wire = SidecarJson.decodeFromJsonElement(
            PostNotificationWire.serializer(),
            checkNotNull(request.params),
        )
        assertEquals(PostNotificationWire("Deferno", "\"Pack for the trip\" is due soon"), wire)
    }

    @Test
    fun postNotificationWireOmitsAnAbsentBody() {
        val json = SidecarJson.encodeToString(PostNotificationWire.serializer(), PostNotificationWire("t"))
        assertFalse(json.contains("body"), json)
    }

    @Test
    fun postNotificationWireRedactsItsUserContent() {
        // Privacy (ADR-0009): a notification's title/body are user content — never in diagnostics.
        val wire = PostNotificationWire(title = "SECRET-TITLE", body = "SECRET-BODY")
        assertFalse(wire.toString().contains("SECRET"), wire.toString())
    }

    @Test
    fun goldenStatusItemFixturesDecode() {
        val request = assertIs<SidecarFrame.Request>(decode(fixture("set-status-item-request.json")))
        assertEquals(SidecarMethods.SetStatusItem, request.method)
        assertEquals(
            SetStatusItemWire(visible = true),
            SidecarJson.decodeFromJsonElement(SetStatusItemWire.serializer(), checkNotNull(request.params)),
        )

        val push = assertIs<SidecarFrame.Push>(decode(fixture("status-item-clicked-push.json")))
        assertEquals(SidecarTopics.StatusItemClicked, push.topic)
    }

    @Test
    fun goldenHotkeyFixturesDecode() {
        val request = assertIs<SidecarFrame.Request>(decode(fixture("register-hotkey-request.json")))
        assertEquals(SidecarMethods.RegisterHotkey, request.method)
        assertEquals(
            RegisterHotkeyWire(id = 1, key = "d", modifiers = setOf(HotkeyModifier.COMMAND, HotkeyModifier.SHIFT)),
            SidecarJson.decodeFromJsonElement(RegisterHotkeyWire.serializer(), checkNotNull(request.params)),
        )

        val push = assertIs<SidecarFrame.Push>(decode(fixture("hotkey-fired-push.json")))
        assertEquals(SidecarTopics.HotkeyFired, push.topic)
        assertEquals(
            HotkeyFiredWire(id = 1),
            SidecarJson.decodeFromJsonElement(HotkeyFiredWire.serializer(), push.payload),
        )
    }

    @Test
    fun hotkeyKeyNamesCoverTheDocumentedSet() {
        // The contract names a–z, 0–9, the named keys, and f1–f12 (26 + 10 + 4 + 12).
        assertEquals(52, SidecarHotkeyKeys.All.size)
        assertTrue(SidecarHotkeyKeys.All.containsAll(setOf("a", "z", "0", "9", "space", "return", "escape", "tab", "f1", "f12")))
    }

    @Test
    fun transcriptWireRoundTripsEveryVariant() {
        for (event in listOf(TranscriptWire.Partial("a"), TranscriptWire.Final("b"), TranscriptWire.Failure("capture"))) {
            val json = SidecarJson.encodeToString(TranscriptWire.serializer(), event)
            assertEquals(event, SidecarJson.decodeFromString(TranscriptWire.serializer(), json))
        }
    }

    @Test
    fun failureToStringRedactsErrorDetails() {
        // Privacy: a Failure's opaque details must not surface in diagnostics (ADR-0009).
        val failure = SidecarFrame.Failure(
            id = 1,
            error = SidecarError(SidecarErrorCode.INTERNAL, "boom", details = JsonPrimitive("SECRET")),
        )
        assertFalse(failure.error.toString().contains("SECRET"), failure.error.toString())
    }

    private fun encode(frame: SidecarFrame): String =
        SidecarJson.encodeToString(SidecarFrame.serializer(), frame)

    private fun decode(json: String): SidecarFrame =
        SidecarJson.decodeFromString(SidecarFrame.serializer(), json)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing golden fixture: $name" }
            .bufferedReader().use { it.readText() }
}
