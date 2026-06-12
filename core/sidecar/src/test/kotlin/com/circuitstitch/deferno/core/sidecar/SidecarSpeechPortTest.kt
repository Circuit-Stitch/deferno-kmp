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
 * Helper's genuine ready signal and **degrades, never throws** — carrying *why* when not ready (#172:
 * absent ≠ capability-missing ≠ permission-blocked ≠ unresponsive); [SidecarSpeechPort.preflight] is
 * the prompting resolution of the dictation gates (#120/#172), owned by the seam so the TCC vocabulary
 * and the Speech→mic prompt order never leak to a consumer; and [SidecarSpeechPort.transcripts] is a
 * tolerant reader (ADR-0005): an undecodable event is dropped, never thrown at the dictation collector.
 */
class SidecarSpeechPortTest {

    // --- readiness: the ready signal, with its reason -------------------------------------------------

    @Test
    fun readyWhenTheHelperAdvertisesSpeechAndPermissionsAreGranted() = runTest {
        val client = FakeSidecarClient(permissionStatus = PermissionStatusValue.GRANTED)

        assertEquals(SidecarSpeechReadiness.Ready, port(client).readiness())
        // Both dictation gates are introspected (#120): Speech first, then mic.
        assertEquals(2, client.requests)
        assertEquals(SidecarMethods.QueryPermission, client.lastRequestMethod)
        assertEquals(
            QueryPermissionWire(SidecarPermissionCapabilities.Microphone),
            SidecarJson.decodeFromJsonElement(QueryPermissionWire.serializer(), client.lastRequestParams!!),
        )
    }

    @Test
    fun reportsNoHelperWhenNothingIsBound() = runTest {
        val client = FakeSidecarClient(connectFailure = SidecarUnavailableException("nothing listening"))
        assertEquals(SidecarSpeechReadiness.NoHelper, port(client).readiness())
    }

    @Test
    fun reportsNotRespondingWhenTheHandshakeIsRefused() = runTest {
        // Something IS bound but refused the peer-auth — present-but-unusable, not an absent fast path.
        val client = FakeSidecarClient(connectFailure = SidecarSecurityException("bad token"))
        assertEquals(SidecarSpeechReadiness.NotResponding, port(client).readiness())
    }

    @Test
    fun reportsCapabilityMissingWithoutTheSpeechCapability() = runTest {
        val client = FakeSidecarClient(advertisedCapabilities = setOf(SidecarCapabilities.Permissions))
        assertEquals(SidecarSpeechReadiness.CapabilityMissing, port(client).readiness())
    }

    @Test
    fun namesTheBlockedGateWhenAPermissionIsDeniedOrRestricted() = runTest {
        for (blocked in listOf(PermissionStatusValue.DENIED, PermissionStatusValue.RESTRICTED)) {
            val client = FakeSidecarClient(permissionStatus = blocked)
            assertEquals(
                // The fake's TCC answers every gate with [blocked]; Speech is introspected first.
                SidecarSpeechReadiness.PermissionBlocked(SidecarDictationPermission.Speech),
                port(client).readiness(),
                "expected $blocked to foreclose readiness, naming the gate",
            )
        }
    }

    @Test
    fun staysReadyWhilePermissionIsNotDetermined() = runTest {
        // Introspection never prompts — the preflight (or first real subscribe) fires the OS prompt, so
        // a not-yet-determined permission must keep the engine selectable (ADR-0024).
        for (open in listOf(PermissionStatusValue.NOT_DETERMINED, PermissionStatusValue.UNKNOWN)) {
            val client = FakeSidecarClient(permissionStatus = open)
            assertEquals(
                SidecarSpeechReadiness.Ready,
                port(client).readiness(),
                "expected $open to stay selectable",
            )
        }
    }

    @Test
    fun skipsThePermissionCheckWhenTheHelperDoesNotBrokerPermissions() = runTest {
        val client = FakeSidecarClient(advertisedCapabilities = setOf(SidecarCapabilities.SpeechTranscribe))
        assertEquals(SidecarSpeechReadiness.Ready, port(client).readiness())
        assertEquals(0, client.requests)
    }

    @Test
    fun reportsNotRespondingWhenThePermissionQueryFails() = runTest {
        // Deliberately stricter than the UX permission port's degrade-to-unknown: a Helper that can't
        // even answer a query is not ready.
        val client = FakeSidecarClient(
            requestFailure = SidecarRequestException(SidecarError(SidecarErrorCode.INTERNAL, "boom")),
        )
        assertEquals(SidecarSpeechReadiness.NotResponding, port(client).readiness())
    }

    @Test
    fun treatsAnUnreadablePermissionReplyAsUnknownAndStaysReady() = runTest {
        // A reply this client can't read ≈ unknown: never a crash out of an availability check, and
        // unknown stays selectable (the preflight or first real subscribe settles it).
        val client = FakeSidecarClient()
        client.requestAnswer = { buildJsonObject { put("garbage", true) } }
        assertEquals(SidecarSpeechReadiness.Ready, port(client).readiness())
    }

    @Test
    fun treatsAMissingPermissionReplyAsUnknownAndStaysReady() = runTest {
        val client = FakeSidecarClient()
        client.requestAnswer = { null } // a no-content ack — nothing to read, nothing to block on
        assertEquals(SidecarSpeechReadiness.Ready, port(client).readiness())
    }

    // --- preflight: the prompting resolution of the dictation gates (#120/#172) -----------------------

    @Test
    fun preflightIsClearOnSettledGrantsWithoutPrompting() = runTest {
        val permissions = FakeSidecarPermissionPort() // everything granted

        assertEquals(SpeechPreflight.Clear, port(permissions = permissions).preflight())
        assertEquals(emptyList(), permissions.requested, "a settled grant must never prompt")
    }

    @Test
    fun preflightNamesTheGateASettledDenialForecloses() = runTest {
        for (settled in listOf(PermissionStatusValue.DENIED, PermissionStatusValue.RESTRICTED)) {
            val permissions = FakeSidecarPermissionPort(
                statuses = mutableMapOf(SidecarPermissionCapabilities.Microphone to settled),
            )

            assertEquals(
                SpeechPreflight.Blocked(SidecarDictationPermission.Microphone),
                port(permissions = permissions).preflight(),
                "for $settled",
            )
            assertEquals(emptyList(), permissions.requested, "a terminal denial must never re-prompt")
        }
    }

    @Test
    fun preflightPromptsSpeechThenMicAndClearsOnGrant() = runTest {
        val permissions = FakeSidecarPermissionPort(
            statuses = mutableMapOf(
                SidecarPermissionCapabilities.Speech to PermissionStatusValue.NOT_DETERMINED,
                SidecarPermissionCapabilities.Microphone to PermissionStatusValue.NOT_DETERMINED,
            ),
            requestOutcome = PermissionStatusValue.GRANTED,
        )

        assertEquals(SpeechPreflight.Clear, port(permissions = permissions).preflight())
        // The discrete not_determined → request → granted flow, in the Helper's own prompt order
        // (protocol-v1.md): Speech first, then mic.
        assertEquals(
            listOf(SidecarPermissionCapabilities.Speech, SidecarPermissionCapabilities.Microphone),
            permissions.requested,
        )
    }

    @Test
    fun preflightBlocksWhenThePromptSettlesDenied() = runTest {
        val permissions = FakeSidecarPermissionPort(
            statuses = mutableMapOf(SidecarPermissionCapabilities.Speech to PermissionStatusValue.NOT_DETERMINED),
            requestOutcome = PermissionStatusValue.DENIED,
        )

        assertEquals(
            SpeechPreflight.Blocked(SidecarDictationPermission.Speech),
            port(permissions = permissions).preflight(),
        )
    }

    @Test
    fun preflightStaysClearOnUnknownWithoutPrompting() = runTest {
        // No Helper-brokered permissions / an unreadable reply: nothing settled, so the subscribe path
        // must stay reachable (its first real use is what would prompt).
        val permissions = FakeSidecarPermissionPort(
            statuses = mutableMapOf(SidecarPermissionCapabilities.Speech to PermissionStatusValue.UNKNOWN),
        )

        assertEquals(SpeechPreflight.Clear, port(permissions = permissions).preflight())
        assertEquals(emptyList(), permissions.requested, "unknown is not not_determined — never prompt on it")
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

        port(client).transcripts().test {
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

        port(client).transcripts().test {
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

        port(client).transcripts().test {
            assertEquals(TranscriptWire.Partial("hel"), awaitItem())
            assertIs<SidecarConnectionLostException>(awaitError())
        }
        assertTrue(client.openedStreams.isNotEmpty())
    }

    private fun port(
        client: SidecarClient = FakeSidecarClient(),
        permissions: SidecarPermissionPort = FakeSidecarPermissionPort(),
    ): DefaultSidecarSpeechPort = DefaultSidecarSpeechPort(client, permissions)

    private fun wire(event: TranscriptWire): JsonElement =
        SidecarJson.encodeToJsonElement(TranscriptWire.serializer(), event)
}
