package com.circuitstitch.deferno.core.speech

import app.cash.turbine.test
import com.circuitstitch.deferno.core.sidecar.SidecarConnectionLostException
import com.circuitstitch.deferno.core.sidecar.SidecarSecurityException
import com.circuitstitch.deferno.core.sidecar.SidecarSpeechReadiness
import com.circuitstitch.deferno.core.sidecar.SidecarUnavailableException
import com.circuitstitch.deferno.core.sidecar.TranscriptWire
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The [SidecarSpeechToText] seam contract (#119, ADR-0024): pure mapping over the [SidecarSpeechPort] —
 * the port's readiness onto [SpeechAvailability] (absent Helper ≠ transiently not ready), the wire
 * [TranscriptWire] onto the domain [TranscriptEvent], and stream failures onto non-PII [SpeechError]s —
 * plus the local English-only gate (ADR-0018). The wire + socket below the port are pinned by
 * core/sidecar's `SidecarSpeechPortTest`; the whole stack over a real socket by
 * [SidecarSpeechToTextSelectorE2ETest].
 */
class SidecarSpeechToTextTest {

    @Test
    fun identifiesItselfAboveTheWhisperFloor() {
        val engine = SidecarSpeechToText(FakeSidecarSpeechPort())
        assertEquals(SpeechEngineId.Sidecar, engine.id)
        assertTrue(engine.rank > 0, "the native fast path must outrank the whisper floor (rank 0)")
        assertTrue(!engine.supportsContinuous, "SFSpeech is short-utterance; Brain dump prefers whisper")
    }

    // --- availability: readiness → SpeechAvailability ------------------------------------------------

    @Test
    fun isAvailableWhenThePortReportsReady() = runTest {
        val engine = SidecarSpeechToText(FakeSidecarSpeechPort(readiness = SidecarSpeechReadiness.Ready))
        assertEquals(SpeechAvailability.Available, engine.availability("en-US"))
    }

    @Test
    fun reportsNotInstalledWhenNoHelperIsBound() = runTest {
        // The permanent absent-fast-path state (Linux/Windows) — NOT a transient NotReady: the Settings
        // row must say "not available on this device", never a forever-"Preparing…" (the #167 review).
        val engine = SidecarSpeechToText(FakeSidecarSpeechPort(readiness = SidecarSpeechReadiness.NoHelper))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NotInstalled),
            engine.availability("en-US"),
        )
    }

    @Test
    fun reportsNotReadyWhenTheHelperIsPresentButNotReady() = runTest {
        val engine = SidecarSpeechToText(FakeSidecarSpeechPort(readiness = SidecarSpeechReadiness.NotReady))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NotReady),
            engine.availability("en-US"),
        )
    }

    @Test
    fun reportsUnsupportedLocaleForNonEnglishWithoutIntrospecting() = runTest {
        val port = FakeSidecarSpeechPort()
        val engine = SidecarSpeechToText(port)

        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale),
            engine.availability("de-DE"),
        )
        assertEquals(0, port.readinessChecks, "the locale gate is local — no socket traffic for non-English")
    }

    // --- listen(): wire → domain at the edge ---------------------------------------------------------

    @Test
    fun mapsPartialAndFinalWireEventsToTranscriptEvents() = runTest {
        val port = FakeSidecarSpeechPort(
            events = listOf(
                TranscriptWire.Partial("hel"),
                TranscriptWire.Partial("hello wor"),
                TranscriptWire.Final("hello world"),
            ),
        )

        SidecarSpeechToText(port).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Partial("hel"), awaitItem())
            assertEquals(TranscriptEvent.Partial("hello wor"), awaitItem())
            assertEquals(TranscriptEvent.Final("hello world"), awaitItem())
            awaitComplete()
        }
        assertEquals(1, port.subscriptions)
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
            val port = FakeSidecarSpeechPort(events = listOf(TranscriptWire.Failure(reason)))
            SidecarSpeechToText(port).listen("en-US", ContinuityHint.Utterance).test {
                assertEquals(TranscriptEvent.Error(expected), awaitItem(), "for reason $reason")
                awaitComplete()
            }
        }
    }

    @Test
    fun emitsUnavailableForANonEnglishLocaleWithoutSubscribing() = runTest {
        val port = FakeSidecarSpeechPort()
        SidecarSpeechToText(port).listen("fr-FR", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Unavailable), awaitItem())
            awaitComplete()
        }
        assertEquals(0, port.subscriptions)
    }

    @Test
    fun emitsUnavailableWhenNoHelperIsBound() = runTest {
        val port = FakeSidecarSpeechPort(failure = SidecarUnavailableException("nothing listening"))
        SidecarSpeechToText(port).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Unavailable), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun emitsUnavailableWhenTheHandshakeIsRefused() = runTest {
        val port = FakeSidecarSpeechPort(failure = SidecarSecurityException("bad token"))
        SidecarSpeechToText(port).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Unavailable), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun emitsEngineErrorWhenTheStreamFailsMidUtterance() = runTest {
        val port = FakeSidecarSpeechPort(
            events = listOf(TranscriptWire.Partial("hel")),
            failure = SidecarConnectionLostException("dropped"),
        )
        SidecarSpeechToText(port).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Partial("hel"), awaitItem())
            assertEquals(TranscriptEvent.Error(SpeechError.Engine), awaitItem())
            awaitComplete()
        }
    }
}
