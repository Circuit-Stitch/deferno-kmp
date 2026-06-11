package com.circuitstitch.deferno.core.speech

import app.cash.turbine.test
import com.circuitstitch.deferno.core.sidecar.PermissionStatusValue
import com.circuitstitch.deferno.core.sidecar.SidecarCapabilities
import com.circuitstitch.deferno.core.sidecar.SidecarClient
import com.circuitstitch.deferno.core.sidecar.StubHelper
import com.circuitstitch.deferno.core.sidecar.unixSocketSidecarClient
import kotlinx.coroutines.runBlocking
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The #119 acceptance proof on the **Linux fast path**: the [SpeechToTextSelector] over a real
 * [SidecarSpeechToText] — real `DefaultSidecarClient`, real AF_UNIX socket, the #118 [StubHelper] as
 * the bound Helper — next to the always-available whisper floor (a [FakeSpeechToText], since the real
 * whisper engine needs a mic + model). It pins the ADR-0024 selection contract end-to-end with no Mac:
 * the sidecar engine wins **only** on the Helper's genuine ready signal, and every degraded state
 * (no Helper bound, capability not advertised, Speech TCC denied, non-English locale, a continuous
 * session) falls to the floor instead of erroring.
 */
class SidecarSpeechToTextSelectorE2ETest {

    private val TOKEN = "shared-in-band-token"

    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanups.asReversed().forEach { runCatching { it() } }
    }

    @Test
    fun picksTheSidecarEngineAndStreamsTranscriptEventsWhenTheHelperIsReady() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = startStub()
        val floor = whisperFloor()
        val selector = selector(client(stub.path), floor)

        assertEquals(SpeechEngineId.Sidecar, selector.select("en-US", ContinuityHint.Utterance)?.id)

        // The stub's canned dictation, mapped frame-by-frame from the socket to the domain seam —
        // text only, no PCM anywhere on this path (the Transcript-altitude seam, ADR-0018).
        selector.listen("en-US", ContinuityHint.Utterance).test {
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
        val unbound = socketDir().resolve("not-bound.sock")
        val floor = whisperFloor()
        val selector = selector(client(unbound), floor)

        assertEquals(SpeechEngineId.Whisper, selector.select("en-US", ContinuityHint.Utterance)?.id)
        selector.listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Final("ok"), awaitItem())
            awaitComplete()
        }
        assertEquals(1, floor.listens)
    }

    @Test
    fun fallsToTheWhisperFloorWhenTheHelperDoesNotAdvertiseSpeech() = runBlocking {
        if (!posixSupported()) return@runBlocking
        // A Helper that genuinely can't construct the recognizer omits the capability (ADR-0025).
        val stub = startStub(capabilities = setOf(SidecarCapabilities.Permissions))
        val selector = selector(client(stub.path), whisperFloor())

        assertEquals(SpeechEngineId.Whisper, selector.select("en-US", ContinuityHint.Utterance)?.id)
    }

    @Test
    fun fallsToTheWhisperFloorWhenSpeechPermissionIsDenied() = runBlocking {
        if (!posixSupported()) return@runBlocking
        val stub = startStub().also { it.permissionStatus = PermissionStatusValue.DENIED }
        val selector = selector(client(stub.path), whisperFloor())

        assertEquals(SpeechEngineId.Whisper, selector.select("en-US", ContinuityHint.Utterance)?.id)
    }

    @Test
    fun prefersTheWhisperFloorForAContinuousSession() = runBlocking {
        if (!posixSupported()) return@runBlocking
        // Helper fully ready — but SFSpeech is short-utterance, so a Brain dump stays on whisper.
        val stub = startStub()
        val selector = selector(client(stub.path), whisperFloor())

        assertEquals(SpeechEngineId.Whisper, selector.select("en-US", ContinuityHint.Continuous)?.id)
    }

    @Test
    fun reportsUnsupportedLocaleForNonEnglishEvenWithNoHelperBound() = runBlocking {
        if (!posixSupported()) return@runBlocking
        // English-only is gated locally (ADR-0018): with nothing listening, the reason is still the
        // locale — never a misleading NotReady, and never a mis-transcription.
        val unbound = socketDir().resolve("not-bound.sock")
        val floor = whisperFloor()
        val selector = selector(client(unbound), floor)

        assertNull(selector.select("de-DE", ContinuityHint.Utterance))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale),
            selector.availability("de-DE"),
        )
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** The selector under test: the real sidecar engine + the floor, rank-picked (Automatic, #93). */
    private fun selector(sidecarClient: SidecarClient, floor: FakeSpeechToText): SpeechToTextSelector =
        SpeechToTextSelector(
            engines = setOf(SidecarSpeechToText(sidecarClient), floor),
            preference = InMemorySpeechEnginePreference(SpeechEngineId.Automatic),
        )

    /** The always-available floor (rank 0). A fake: the real whisper engine needs a mic + model. */
    private fun whisperFloor(): FakeSpeechToText = FakeSpeechToText(
        id = SpeechEngineId.Whisper,
        rank = 0,
        supportsContinuous = true,
        availabilityForLocale = { locale ->
            if (locale.lowercase().startsWith("en")) {
                SpeechAvailability.Available
            } else {
                SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale)
            }
        },
    )

    private fun startStub(
        capabilities: Set<String>? = null,
    ): StubHelper {
        val path = socketDir().resolve("h.sock")
        val stub = if (capabilities == null) {
            StubHelper(path, expectedToken = TOKEN)
        } else {
            StubHelper(path, expectedToken = TOKEN, capabilities = capabilities)
        }
        stub.start()
        cleanups += { stub.close() }
        return stub
    }

    private fun client(path: Path): SidecarClient =
        unixSocketSidecarClient(path, TOKEN).also { client -> cleanups += { client.close() } }

    private fun socketDir(): Path {
        val tmp = Paths.get("/tmp")
        val base = if (Files.isDirectory(tmp) && Files.isWritable(tmp)) tmp else Paths.get(System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(base, "deferno-sidecar")
        cleanups += { runCatching { dir.toFile().deleteRecursively() } }
        return dir
    }

    private fun posixSupported(): Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
}
