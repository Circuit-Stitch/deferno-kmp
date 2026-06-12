package com.circuitstitch.deferno.core.speech

import app.cash.turbine.test
import com.circuitstitch.deferno.core.sidecar.DefaultSidecarPermissionPort
import com.circuitstitch.deferno.core.sidecar.DefaultSidecarSpeechPort
import com.circuitstitch.deferno.core.sidecar.PermissionStatusValue
import com.circuitstitch.deferno.core.sidecar.SidecarCapabilities
import com.circuitstitch.deferno.core.sidecar.SidecarClient
import com.circuitstitch.deferno.core.sidecar.SidecarTestHarness
import com.circuitstitch.deferno.core.sidecar.SidecarTestHarness.Companion.DEADLINE
import com.circuitstitch.deferno.core.sidecar.SidecarTestHarness.Companion.posixSupported
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The #119 acceptance proof on the **Linux fast path**: the [SpeechToTextSelector] over a real
 * [SidecarSpeechToText] — real `DefaultSidecarClient` + [DefaultSidecarSpeechPort], real AF_UNIX
 * socket, the #118 `StubHelper` as the bound Helper (all rigged by the shared [SidecarTestHarness]) —
 * next to the always-available whisper floor (a [FakeSpeechToText], since the real whisper engine
 * needs a mic + model). It pins the ADR-0024 selection contract end-to-end with no Mac: the sidecar
 * engine wins **only** on the Helper's genuine ready signal, and every degraded state (no Helper
 * bound, capability not advertised, Speech TCC denied, non-English locale, a continuous session)
 * falls to the floor instead of erroring.
 *
 * Real-socket Turbine blocks carry the shared [SidecarTestHarness.DEADLINE] — Turbine's default 3s
 * `awaitItem` flaked on a starved CI runner (#161).
 */
class SidecarSpeechToTextSelectorE2ETest {

    private val harness = SidecarTestHarness()

    @AfterTest
    fun tearDown() = harness.close()

    @Test
    fun picksTheSidecarEngineAndStreamsTranscriptEventsWhenTheHelperIsReady() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub()
        val floor = whisperFloor()
        val selector = selector(harness.client(stub.path), floor)

        assertEquals(SpeechEngineId.Sidecar, selector.select("en-US", ContinuityHint.Utterance)?.id)

        // The stub's canned dictation, mapped frame-by-frame from the socket to the domain seam —
        // text only, no PCM anywhere on this path (the Transcript-altitude seam, ADR-0018).
        selector.listen("en-US", ContinuityHint.Utterance).test(timeout = DEADLINE) {
            assertEquals(TranscriptEvent.Partial("hel"), awaitItem())
            assertEquals(TranscriptEvent.Partial("hello wor"), awaitItem())
            assertEquals(TranscriptEvent.Final("hello world"), awaitItem())
            awaitComplete()
        }
        assertEquals(0, floor.listens, "the ready fast path must win — whisper stays idle")
    }

    @Test
    fun fallsToTheWhisperFloorWhenNoHelperIsBound() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val unbound = harness.socketDir().resolve("not-bound.sock")
        val floor = whisperFloor()
        val selector = selector(harness.client(unbound), floor)

        assertEquals(SpeechEngineId.Whisper, selector.select("en-US", ContinuityHint.Utterance)?.id)
        selector.listen("en-US", ContinuityHint.Utterance).test(timeout = DEADLINE) {
            assertEquals(TranscriptEvent.Final("ok"), awaitItem())
            awaitComplete()
        }
        assertEquals(1, floor.listens)
    }

    @Test
    fun fallsToTheWhisperFloorWhenTheHelperDoesNotAdvertiseSpeech() = runBlocking {
        if (!posixSupported()) return@runBlocking
        // A Helper that genuinely can't construct the recognizer omits the capability (ADR-0025).
        val stub = harness.startStub(capabilities = setOf(SidecarCapabilities.Permissions))
        val selector = selector(harness.client(stub.path), whisperFloor())

        assertEquals(SpeechEngineId.Whisper, selector.select("en-US", ContinuityHint.Utterance)?.id)
    }

    @Test
    fun fallsToTheWhisperFloorWhenSpeechPermissionIsDenied() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = harness.startStub().also { it.permissionStatus = PermissionStatusValue.DENIED }
        val selector = selector(harness.client(stub.path), whisperFloor())

        assertEquals(SpeechEngineId.Whisper, selector.select("en-US", ContinuityHint.Utterance)?.id)
    }

    @Test
    fun prefersTheWhisperFloorForAContinuousSession() = runBlocking {
        if (!posixSupported()) return@runBlocking
        // Helper fully ready — but SFSpeech is short-utterance, so a Brain dump stays on whisper.
        val stub = harness.startStub()
        val selector = selector(harness.client(stub.path), whisperFloor())

        assertEquals(SpeechEngineId.Whisper, selector.select("en-US", ContinuityHint.Continuous)?.id)
    }

    @Test
    fun reportsUnsupportedLocaleForNonEnglishEvenWithNoHelperBound() = runBlocking {
        if (!posixSupported()) return@runBlocking
        // English-only is gated locally (ADR-0018): with nothing listening, the reason is still the
        // locale — never a misleading NotReady, and never a mis-transcription.
        val unbound = harness.socketDir().resolve("not-bound.sock")
        val floor = whisperFloor()
        val selector = selector(harness.client(unbound), floor)

        assertNull(selector.select("de-DE", ContinuityHint.Utterance))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale),
            selector.availability("de-DE"),
        )
    }

    @Test
    fun anAbsentHelperNeverMasksTheFloorsReason() = runBlocking {
        if (!posixSupported()) return@runBlocking
        // Fresh install, no Helper, whisper model still arriving: the mic affordance must surface the
        // actionable ModelMissing ("Downloading…"), not the higher-ranked engine's permanent absence.
        val unbound = harness.socketDir().resolve("not-bound.sock")
        val floor = FakeSpeechToText(
            id = SpeechEngineId.Whisper,
            rank = 0,
            availability = SpeechAvailability.Unavailable(UnavailableReason.ModelMissing),
        )
        val selector = selector(harness.client(unbound), floor)

        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.ModelMissing),
            selector.availability("en-US"),
        )
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** The selector under test: the real sidecar engine + the floor, rank-picked (Automatic, #93). */
    private fun selector(sidecarClient: SidecarClient, floor: FakeSpeechToText): SpeechToTextSelector =
        SpeechToTextSelector(
            engines = setOf(
                // The real #120 permission port too: listen()'s preflight runs against the stub Helper.
                SidecarSpeechToText(
                    DefaultSidecarSpeechPort(sidecarClient),
                    DefaultSidecarPermissionPort(sidecarClient),
                ),
                floor,
            ),
            preference = InMemorySpeechEnginePreference(SpeechEngineId.Automatic),
        )

    /** The always-available floor (rank 0). A fake: the real whisper engine needs a mic + model. */
    private fun whisperFloor(): FakeSpeechToText = FakeSpeechToText(
        id = SpeechEngineId.Whisper,
        rank = 0,
        supportsContinuous = true,
        availabilityForLocale = { locale ->
            if (locale.isEnglishLocale()) {
                SpeechAvailability.Available
            } else {
                SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale)
            }
        },
    )
}
