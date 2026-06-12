package com.circuitstitch.deferno.core.speech

import app.cash.turbine.test
import com.circuitstitch.deferno.core.sidecar.PermissionStatusValue
import com.circuitstitch.deferno.core.sidecar.SidecarConnectionLostException
import com.circuitstitch.deferno.core.sidecar.SidecarPermissionCapabilities
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
        val engine = engine(FakeSidecarSpeechPort())
        assertEquals(SpeechEngineId.Sidecar, engine.id)
        assertTrue(engine.rank > 0, "the native fast path must outrank the whisper floor (rank 0)")
        assertTrue(!engine.supportsContinuous, "SFSpeech is short-utterance; Brain dump prefers whisper")
    }

    // --- availability: readiness → SpeechAvailability ------------------------------------------------

    @Test
    fun isAvailableWhenThePortReportsReady() = runTest {
        val engine = engine(FakeSidecarSpeechPort(readiness = SidecarSpeechReadiness.Ready))
        assertEquals(SpeechAvailability.Available, engine.availability("en-US"))
    }

    @Test
    fun reportsNotInstalledWhenNoHelperIsBound() = runTest {
        // The permanent absent-fast-path state (Linux/Windows) — NOT a transient NotReady: the Settings
        // row must say "not available on this device", never a forever-"Preparing…" (the #167 review).
        val engine = engine(FakeSidecarSpeechPort(readiness = SidecarSpeechReadiness.NoHelper))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NotInstalled),
            engine.availability("en-US"),
        )
    }

    @Test
    fun reportsNotReadyWhenTheHelperIsPresentButNotReady() = runTest {
        val engine = engine(FakeSidecarSpeechPort(readiness = SidecarSpeechReadiness.NotReady))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NotReady),
            engine.availability("en-US"),
        )
    }

    @Test
    fun reportsUnsupportedLocaleForNonEnglishWithoutIntrospecting() = runTest {
        val port = FakeSidecarSpeechPort()
        val engine = engine(port)

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

        engine(port).listen("en-US", ContinuityHint.Utterance).test {
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
            // The Helper *knows* TCC refused — typed (#120), the in-stream backstop for a denial
            // landing mid-flight (revoked in Settings after the preflight passed).
            "microphone-permission-denied" to SpeechError.PermissionDenied,
            "speech-permission-denied" to SpeechError.PermissionDenied,
            "engine-busy" to SpeechError.Capture,
            "recognizer-unavailable" to SpeechError.Engine,
            "recognition-failed" to SpeechError.Engine,
            "some-newer-reason" to SpeechError.Engine,
        )
        for ((reason, expected) in cases) {
            val port = FakeSidecarSpeechPort(events = listOf(TranscriptWire.Failure(reason)))
            engine(port).listen("en-US", ContinuityHint.Utterance).test {
                assertEquals(TranscriptEvent.Error(expected), awaitItem(), "for reason $reason")
                awaitComplete()
            }
        }
    }

    @Test
    fun emitsUnavailableForANonEnglishLocaleWithoutSubscribing() = runTest {
        val port = FakeSidecarSpeechPort()
        engine(port).listen("fr-FR", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Unavailable), awaitItem())
            awaitComplete()
        }
        assertEquals(0, port.subscriptions)
    }

    @Test
    fun emitsUnavailableWhenNoHelperIsBound() = runTest {
        val port = FakeSidecarSpeechPort(failure = SidecarUnavailableException("nothing listening"))
        engine(port).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Unavailable), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun emitsUnavailableWhenTheHandshakeIsRefused() = runTest {
        val port = FakeSidecarSpeechPort(failure = SidecarSecurityException("bad token"))
        engine(port).listen("en-US", ContinuityHint.Utterance).test {
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
        engine(port).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Partial("hel"), awaitItem())
            assertEquals(TranscriptEvent.Error(SpeechError.Engine), awaitItem())
            awaitComplete()
        }
    }

    // --- listen(): the #120 permission preflight ------------------------------------------------------

    @Test
    fun grantedPermissionsStreamWithoutPrompting() = runTest {
        val port = FakeSidecarSpeechPort(events = listOf(TranscriptWire.Final("hello")))
        val permissions = FakeSidecarPermissionPort() // everything granted

        engine(port, permissions).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Final("hello"), awaitItem())
            awaitComplete()
        }
        assertEquals(emptyList(), permissions.requested, "a settled grant must never prompt")
    }

    @Test
    fun aSettledDenialForeclosesWithoutPromptingOrTouchingTheMic() = runTest {
        for (settled in listOf(PermissionStatusValue.DENIED, PermissionStatusValue.RESTRICTED)) {
            val port = FakeSidecarSpeechPort(events = listOf(TranscriptWire.Final("never")))
            val permissions = FakeSidecarPermissionPort(
                statuses = mutableMapOf(SidecarPermissionCapabilities.Microphone to settled),
            )

            engine(port, permissions).listen("en-US", ContinuityHint.Utterance).test {
                assertEquals(TranscriptEvent.Error(SpeechError.PermissionDenied), awaitItem(), "for $settled")
                awaitComplete()
            }
            assertEquals(0, port.subscriptions, "a foreclosed permission must never engage the Helper's mic")
            assertEquals(emptyList(), permissions.requested, "a terminal denial must never re-prompt")
        }
    }

    @Test
    fun notDeterminedPromptsSpeechThenMicAndStreamsOnGrant() = runTest {
        val port = FakeSidecarSpeechPort(events = listOf(TranscriptWire.Final("hello")))
        val permissions = FakeSidecarPermissionPort(
            statuses = mutableMapOf(
                SidecarPermissionCapabilities.Speech to PermissionStatusValue.NOT_DETERMINED,
                SidecarPermissionCapabilities.Microphone to PermissionStatusValue.NOT_DETERMINED,
            ),
            requestOutcome = PermissionStatusValue.GRANTED,
        )

        engine(port, permissions).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Final("hello"), awaitItem())
            awaitComplete()
        }
        // The discrete not-determined → request → granted flow, in the Helper's own prompt order.
        assertEquals(
            listOf(SidecarPermissionCapabilities.Speech, SidecarPermissionCapabilities.Microphone),
            permissions.requested,
        )
    }

    @Test
    fun notDeterminedSettlingDeniedEmitsTheTypedPermissionError() = runTest {
        val port = FakeSidecarSpeechPort(events = listOf(TranscriptWire.Final("never")))
        val permissions = FakeSidecarPermissionPort(
            statuses = mutableMapOf(SidecarPermissionCapabilities.Speech to PermissionStatusValue.NOT_DETERMINED),
            requestOutcome = PermissionStatusValue.DENIED,
        )

        engine(port, permissions).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.PermissionDenied), awaitItem())
            awaitComplete()
        }
        assertEquals(0, port.subscriptions)
    }

    @Test
    fun unknownPermissionStateStaysOpenAndSubscribes() = runTest {
        // No Helper-brokered permissions / an unreadable reply: introspection has nothing to say, so
        // the subscribe path must stay reachable (its first real use is what would prompt).
        val port = FakeSidecarSpeechPort(events = listOf(TranscriptWire.Final("hello")))
        val permissions = FakeSidecarPermissionPort(
            statuses = mutableMapOf(SidecarPermissionCapabilities.Speech to PermissionStatusValue.UNKNOWN),
        )

        engine(port, permissions).listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Final("hello"), awaitItem())
            awaitComplete()
        }
        assertEquals(1, port.subscriptions)
        assertEquals(emptyList(), permissions.requested, "unknown is not not_determined — never prompt on it")
    }

    private fun engine(
        port: FakeSidecarSpeechPort,
        permissions: FakeSidecarPermissionPort = FakeSidecarPermissionPort(),
    ): SidecarSpeechToText = SidecarSpeechToText(port, permissions)
}
