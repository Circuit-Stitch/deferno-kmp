package com.circuitstitch.deferno.core.speech

import app.cash.turbine.test
import com.circuitstitch.deferno.core.sidecar.PermissionStatusValue
import com.circuitstitch.deferno.core.sidecar.QueryPermissionWire
import com.circuitstitch.deferno.core.sidecar.SidecarCapabilities
import com.circuitstitch.deferno.core.sidecar.SidecarConnectionLostException
import com.circuitstitch.deferno.core.sidecar.SidecarError
import com.circuitstitch.deferno.core.sidecar.SidecarErrorCode
import com.circuitstitch.deferno.core.sidecar.SidecarJson
import com.circuitstitch.deferno.core.sidecar.SidecarMethods
import com.circuitstitch.deferno.core.sidecar.SidecarRequestException
import com.circuitstitch.deferno.core.sidecar.SidecarUnavailableException
import com.circuitstitch.deferno.core.sidecar.TranscriptWire
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The [SidecarSpeechToText] seam contract (#119, ADR-0024): availability is the Helper's genuine ready
 * signal (connected + capability + Speech TCC not foreclosed), and `listen()` condenses the wire
 * [TranscriptWire] to the domain [TranscriptEvent] at the edge — every protocol outcome mapped to a
 * non-PII [SpeechError], never an unhandled crash. The same engine over the real socket is proven by
 * [SidecarSpeechToTextSelectorE2ETest].
 */
class SidecarSpeechToTextTest {

    @Test
    fun identifiesItselfAboveTheWhisperFloor() {
        val engine = SidecarSpeechToText(FakeSidecarClient())
        assertEquals(SpeechEngineId.Sidecar, engine.id)
        assertTrue(engine.rank > 0, "the native fast path must outrank the whisper floor (rank 0)")
        assertTrue(!engine.supportsContinuous, "SFSpeech is short-utterance; Brain dump prefers whisper")
    }

    // --- availability: the ready signal -------------------------------------------------------------

    @Test
    fun isAvailableWhenTheHelperIsReadyAndPermissionGranted() = runTest {
        val client = FakeSidecarClient(permissionStatus = PermissionStatusValue.GRANTED)
        val engine = SidecarSpeechToText(client)

        assertEquals(SpeechAvailability.Available, engine.availability("en-US"))
        assertEquals(SidecarMethods.QueryPermission, client.lastRequestMethod)
        assertEquals(
            QueryPermissionWire("speech"),
            SidecarJson.decodeFromJsonElement(QueryPermissionWire.serializer(), client.lastRequestParams!!),
        )
    }

    @Test
    fun reportsUnsupportedLocaleForNonEnglishWithoutDialing() = runTest {
        val client = FakeSidecarClient()
        val engine = SidecarSpeechToText(client)

        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale),
            engine.availability("de-DE"),
        )
        assertEquals(0, client.connects, "the locale gate is local — no socket traffic for non-English")
    }

    @Test
    fun reportsNotReadyWhenNoHelperIsBound() = runTest {
        val client = FakeSidecarClient(connectFailure = SidecarUnavailableException("nothing listening"))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NotReady),
            SidecarSpeechToText(client).availability("en-US"),
        )
    }

    @Test
    fun reportsNotReadyWhenTheHelperLacksTheSpeechCapability() = runTest {
        val client = FakeSidecarClient(advertisedCapabilities = setOf(SidecarCapabilities.Permissions))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NotReady),
            SidecarSpeechToText(client).availability("en-US"),
        )
    }

    @Test
    fun reportsNotReadyWhenSpeechPermissionIsDeniedOrRestricted() = runTest {
        for (blocked in listOf(PermissionStatusValue.DENIED, PermissionStatusValue.RESTRICTED)) {
            val client = FakeSidecarClient(permissionStatus = blocked)
            assertEquals(
                SpeechAvailability.Unavailable(UnavailableReason.NotReady),
                SidecarSpeechToText(client).availability("en-US"),
                "expected $blocked to foreclose availability",
            )
        }
    }

    @Test
    fun staysAvailableWhilePermissionIsNotDetermined() = runTest {
        // Introspection never prompts — the first real subscribe fires the OS prompt, so a
        // not-yet-determined permission must keep the engine selectable (ADR-0024).
        for (open in listOf(PermissionStatusValue.NOT_DETERMINED, PermissionStatusValue.UNKNOWN)) {
            val client = FakeSidecarClient(permissionStatus = open)
            assertEquals(
                SpeechAvailability.Available,
                SidecarSpeechToText(client).availability("en-US"),
                "expected $open to stay selectable",
            )
        }
    }

    @Test
    fun skipsThePermissionCheckWhenTheHelperDoesNotBrokerPermissions() = runTest {
        val client = FakeSidecarClient(advertisedCapabilities = setOf(SidecarCapabilities.SpeechTranscribe))
        assertEquals(SpeechAvailability.Available, SidecarSpeechToText(client).availability("en-US"))
        assertEquals(0, client.requests)
    }

    @Test
    fun reportsNotReadyWhenThePermissionQueryFails() = runTest {
        val client = FakeSidecarClient(
            requestFailure = SidecarRequestException(SidecarError(SidecarErrorCode.INTERNAL, "boom")),
        )
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NotReady),
            SidecarSpeechToText(client).availability("en-US"),
        )
    }

    // --- listen(): wire → domain at the edge ---------------------------------------------------------

    @Test
    fun mapsPartialAndFinalWireEventsToTranscriptEvents() = runTest {
        val client = FakeSidecarClient(
            streamEvents = listOf(
                wire(TranscriptWire.Partial("hel")),
                wire(TranscriptWire.Partial("hello wor")),
                wire(TranscriptWire.Final("hello world")),
            ),
        )

        SidecarSpeechToText(client).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Partial("hel"), awaitItem())
            assertEquals(TranscriptEvent.Partial("hello wor"), awaitItem())
            assertEquals(TranscriptEvent.Final("hello world"), awaitItem())
            awaitComplete()
        }
        assertEquals(listOf(SidecarMethods.SubscribeTranscript), client.openedStreams)
    }

    @Test
    fun mapsTheHelpersFailureReasonsToNonPiiSpeechErrors() = runTest {
        val cases = mapOf(
            "microphone-permission-denied" to SpeechError.Capture,
            "engine-busy" to SpeechError.Capture,
            "speech-permission-denied" to SpeechError.Engine,
            "recognizer-unavailable" to SpeechError.Engine,
            "recognition-failed" to SpeechError.Engine,
            "some-newer-reason" to SpeechError.Engine,
        )
        for ((reason, expected) in cases) {
            val client = FakeSidecarClient(streamEvents = listOf(wire(TranscriptWire.Failure(reason))))
            SidecarSpeechToText(client).listen("en-US", ContinuityHint.Utterance).test {
                assertEquals(TranscriptEvent.Error(expected), awaitItem(), "for reason $reason")
                awaitComplete()
            }
        }
    }

    @Test
    fun emitsUnavailableForANonEnglishLocaleWithoutOpeningAStream() = runTest {
        val client = FakeSidecarClient()
        SidecarSpeechToText(client).listen("fr-FR", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Unavailable), awaitItem())
            awaitComplete()
        }
        assertTrue(client.openedStreams.isEmpty())
    }

    @Test
    fun emitsUnavailableWhenNoHelperIsBound() = runTest {
        val client = FakeSidecarClient(connectFailure = SidecarUnavailableException("nothing listening"))
        SidecarSpeechToText(client).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Unavailable), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun emitsEngineErrorWhenTheStreamFailsMidUtterance() = runTest {
        val client = FakeSidecarClient(
            streamEvents = listOf(wire(TranscriptWire.Partial("hel"))),
            streamFailure = SidecarConnectionLostException("dropped"),
        )
        SidecarSpeechToText(client).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Partial("hel"), awaitItem())
            assertEquals(TranscriptEvent.Error(SpeechError.Engine), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun emitsEngineErrorForAnUnreadableWireEvent() = runTest {
        val alien = buildJsonObject { put("type", "alien-event") }
        val client = FakeSidecarClient(streamEvents = listOf(alien))
        SidecarSpeechToText(client).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Engine), awaitItem())
            awaitComplete()
        }
    }

    private fun wire(event: TranscriptWire): JsonElement =
        SidecarJson.encodeToJsonElement(TranscriptWire.serializer(), event)
}
