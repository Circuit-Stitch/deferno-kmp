package com.circuitstitch.deferno.core.sidecar

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The [DefaultSidecarSpeechPort] contract (#119, ADR-0024): [SidecarSpeechPort.readiness] is the
 * Helper's genuine ready signal and **degrades, never throws** — absent (NoHelper) is distinguished
 * from present-but-not-ready (NotReady) — and [SidecarSpeechPort.transcripts] is a tolerant reader
 * (ADR-0005): an undecodable event is dropped, never thrown at the dictation collector.
 */
class SidecarSpeechPortTest {

    // --- readiness: the ready signal -----------------------------------------------------------------

    @Test
    fun readyWhenTheHelperAdvertisesSpeechAndPermissionIsGranted() = runTest {
        val client = FakeSidecarClient(permissionStatus = PermissionStatusValue.GRANTED)

        assertEquals(SidecarSpeechReadiness.Ready, DefaultSidecarSpeechPort(client).readiness())
        assertEquals(SidecarMethods.QueryPermission, client.lastRequestMethod)
        assertEquals(
            QueryPermissionWire(SidecarPermissionCapabilities.Speech),
            SidecarJson.decodeFromJsonElement(QueryPermissionWire.serializer(), client.lastRequestParams!!),
        )
    }

    @Test
    fun reportsNoHelperWhenNothingIsBound() = runTest {
        val client = FakeSidecarClient(connectFailure = SidecarUnavailableException("nothing listening"))
        assertEquals(SidecarSpeechReadiness.NoHelper, DefaultSidecarSpeechPort(client).readiness())
    }

    @Test
    fun reportsNotReadyWhenTheHandshakeIsRefused() = runTest {
        // Something IS bound but refused the peer-auth — present-but-unusable, not an absent fast path.
        val client = FakeSidecarClient(connectFailure = SidecarSecurityException("bad token"))
        assertEquals(SidecarSpeechReadiness.NotReady, DefaultSidecarSpeechPort(client).readiness())
    }

    @Test
    fun reportsNotReadyWithoutTheSpeechCapability() = runTest {
        val client = FakeSidecarClient(advertisedCapabilities = setOf(SidecarCapabilities.Permissions))
        assertEquals(SidecarSpeechReadiness.NotReady, DefaultSidecarSpeechPort(client).readiness())
    }

    @Test
    fun reportsNotReadyWhenSpeechPermissionIsDeniedOrRestricted() = runTest {
        for (blocked in listOf(PermissionStatusValue.DENIED, PermissionStatusValue.RESTRICTED)) {
            val client = FakeSidecarClient(permissionStatus = blocked)
            assertEquals(
                SidecarSpeechReadiness.NotReady,
                DefaultSidecarSpeechPort(client).readiness(),
                "expected $blocked to foreclose readiness",
            )
        }
    }

    @Test
    fun staysReadyWhilePermissionIsNotDetermined() = runTest {
        // Introspection never prompts — the first real subscribe fires the OS prompt, so a
        // not-yet-determined permission must keep the engine selectable (ADR-0024).
        for (open in listOf(PermissionStatusValue.NOT_DETERMINED, PermissionStatusValue.UNKNOWN)) {
            val client = FakeSidecarClient(permissionStatus = open)
            assertEquals(
                SidecarSpeechReadiness.Ready,
                DefaultSidecarSpeechPort(client).readiness(),
                "expected $open to stay selectable",
            )
        }
    }

    @Test
    fun skipsThePermissionCheckWhenTheHelperDoesNotBrokerPermissions() = runTest {
        val client = FakeSidecarClient(advertisedCapabilities = setOf(SidecarCapabilities.SpeechTranscribe))
        assertEquals(SidecarSpeechReadiness.Ready, DefaultSidecarSpeechPort(client).readiness())
        assertEquals(0, client.requests)
    }

    @Test
    fun reportsNotReadyWhenThePermissionQueryFails() = runTest {
        val client = FakeSidecarClient(
            requestFailure = SidecarRequestException(SidecarError(SidecarErrorCode.INTERNAL, "boom")),
        )
        assertEquals(SidecarSpeechReadiness.NotReady, DefaultSidecarSpeechPort(client).readiness())
    }

    @Test
    fun treatsAnUnreadablePermissionReplyAsUnknownAndStaysReady() = runTest {
        // A reply this client can't read ≈ unknown: never a crash out of an availability check, and
        // unknown stays selectable (the first real subscribe settles it).
        val client = FakeSidecarClient()
        client.requestAnswer = { buildJsonObject { put("garbage", true) } }
        assertEquals(SidecarSpeechReadiness.Ready, DefaultSidecarSpeechPort(client).readiness())
    }

    @Test
    fun treatsAMissingPermissionReplyAsUnknownAndStaysReady() = runTest {
        val client = FakeSidecarClient()
        client.requestAnswer = { null } // a no-content ack — nothing to read, nothing to block on
        assertEquals(SidecarSpeechReadiness.Ready, DefaultSidecarSpeechPort(client).readiness())
    }

    // --- transcripts: the tolerant stream ------------------------------------------------------------

    @Test
    fun decodesTheTranscriptStreamInOrder() = runTest {
        val client = FakeSidecarClient(
            streamEvents = listOf(
                wire(TranscriptWire.Partial("hel")),
                wire(TranscriptWire.Partial("hello wor")),
                wire(TranscriptWire.Final("hello world")),
            ),
        )

        DefaultSidecarSpeechPort(client).transcripts().test {
            assertEquals(TranscriptWire.Partial("hel"), awaitItem())
            assertEquals(TranscriptWire.Partial("hello wor"), awaitItem())
            assertEquals(TranscriptWire.Final("hello world"), awaitItem())
            awaitComplete()
        }
        assertEquals(listOf(SidecarMethods.SubscribeTranscript), client.openedStreams)
    }

    @Test
    fun dropsAnUnreadableEventAndKeepsStreaming() = runTest {
        // Tolerant reader (ADR-0005): a newer Helper's event type must not kill dictation mid-utterance.
        val alien = buildJsonObject { put("type", "alien-event") }
        val client = FakeSidecarClient(
            streamEvents = listOf(alien, wire(TranscriptWire.Partial("hel")), wire(TranscriptWire.Final("hello"))),
        )

        DefaultSidecarSpeechPort(client).transcripts().test {
            assertEquals(TranscriptWire.Partial("hel"), awaitItem())
            assertEquals(TranscriptWire.Final("hello"), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun passesTheStreamFailureThroughToTheCollector() = runTest {
        val client = FakeSidecarClient(
            streamEvents = listOf(wire(TranscriptWire.Partial("hel"))),
            streamFailure = SidecarConnectionLostException("dropped"),
        )

        DefaultSidecarSpeechPort(client).transcripts().test {
            assertEquals(TranscriptWire.Partial("hel"), awaitItem())
            assertIs<SidecarConnectionLostException>(awaitError())
        }
        assertTrue(client.openedStreams.isNotEmpty())
    }

    private fun wire(event: TranscriptWire): JsonElement =
        SidecarJson.encodeToJsonElement(TranscriptWire.serializer(), event)
}
