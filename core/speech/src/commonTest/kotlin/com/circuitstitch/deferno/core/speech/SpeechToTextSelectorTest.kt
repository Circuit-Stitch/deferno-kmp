package com.circuitstitch.deferno.core.speech

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The [SpeechToTextSelector] selection rules (ADR-0018): structural never-cloud, rank floor, preference, continuity. */
class SpeechToTextSelectorTest {

    private val whisperId = SpeechEngineId.Whisper
    private val nativeId = SpeechEngineId("native-fast-path")

    /** A preference that never matches a registered engine, so selection is purely rank-based. */
    private fun noPreference() = InMemorySpeechEnginePreference(SpeechEngineId("none"))

    private fun selector(vararg engines: SpeechToText, preference: SpeechEnginePreference = noPreference()) =
        SpeechToTextSelector(engines.toSet(), preference)

    @Test
    fun noEnginesRegistered_selectsNull_andReportsNoEngine() = runTest {
        val selector = selector()
        assertNull(selector.select("en-US", ContinuityHint.Utterance))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NoEngine),
            selector.availability("en-US"),
        )
    }

    @Test
    fun allEnginesUnavailable_selectsNull_andSurfacesHighestRankReason() = runTest {
        val whisper = FakeSpeechToText(whisperId, rank = 0, availability = SpeechAvailability.Unavailable(UnavailableReason.ModelMissing))
        val native = FakeSpeechToText(nativeId, rank = 10, availability = SpeechAvailability.Unavailable(UnavailableReason.NotReady))
        val selector = selector(whisper, native)

        assertNull(selector.select("en-US", ContinuityHint.Utterance))
        // The highest-ranked engine's reason is surfaced so the UI can explain why.
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NotReady),
            selector.availability("en-US"),
        )
    }

    @Test
    fun whisperIsTheFloor_chosenWhenItIsTheOnlyAvailableEngine() = runTest {
        val whisper = FakeSpeechToText(whisperId, rank = 0, availability = SpeechAvailability.Available)
        val native = FakeSpeechToText(nativeId, rank = 10, availability = SpeechAvailability.Unavailable(UnavailableReason.NotReady))
        val selector = selector(whisper, native)

        assertEquals(whisperId, selector.select("en-US", ContinuityHint.Utterance)?.id)
        assertEquals(SpeechAvailability.Available, selector.availability("en-US"))
    }

    @Test
    fun availableNativeFastPath_outranksAvailableWhisper() = runTest {
        val whisper = FakeSpeechToText(whisperId, rank = 0, availability = SpeechAvailability.Available)
        val native = FakeSpeechToText(nativeId, rank = 10, availability = SpeechAvailability.Available)
        val selector = selector(whisper, native)

        assertEquals(nativeId, selector.select("en-US", ContinuityHint.Utterance)?.id)
    }

    @Test
    fun explicitPreference_winsOverRank_whenPreferredEngineAvailable() = runTest {
        val whisper = FakeSpeechToText(whisperId, rank = 0, availability = SpeechAvailability.Available)
        val native = FakeSpeechToText(nativeId, rank = 10, availability = SpeechAvailability.Available)
        val selector = selector(whisper, native, preference = InMemorySpeechEnginePreference(whisperId))

        // Whisper is lower-ranked but explicitly preferred — and available — so it wins.
        assertEquals(whisperId, selector.select("en-US", ContinuityHint.Utterance)?.id)
    }

    @Test
    fun explicitPreference_ignored_whenPreferredEngineUnavailable_fallsToRank() = runTest {
        val whisper = FakeSpeechToText(whisperId, rank = 0, availability = SpeechAvailability.Available)
        val native = FakeSpeechToText(nativeId, rank = 10, availability = SpeechAvailability.Unavailable(UnavailableReason.NotReady))
        // Prefer the native engine, but it isn't available — fall back to the available whisper floor.
        val selector = selector(whisper, native, preference = InMemorySpeechEnginePreference(nativeId))

        assertEquals(whisperId, selector.select("en-US", ContinuityHint.Utterance)?.id)
    }

    @Test
    fun continuousHint_dropsEnginesThatDoNotSupportContinuous() = runTest {
        val whisper = FakeSpeechToText(whisperId, rank = 0, supportsContinuous = true, availability = SpeechAvailability.Available)
        val native = FakeSpeechToText(nativeId, rank = 10, supportsContinuous = false, availability = SpeechAvailability.Available)
        val selector = selector(whisper, native)

        // For an utterance the higher-ranked native engine wins; for a continuous session it is dropped
        // and the streaming whisper baseline is chosen (ADR-0018 continuityHint → Brain dump).
        assertEquals(nativeId, selector.select("en-US", ContinuityHint.Utterance)?.id)
        assertEquals(whisperId, selector.select("en-US", ContinuityHint.Continuous)?.id)
    }

    @Test
    fun nonEnglishLocale_reportsUnavailable_neverMisTranscribes() = runTest {
        // An English-only engine (the v1 whisper behaviour): Available for en-*, Unavailable otherwise.
        val whisper = FakeSpeechToText(
            whisperId,
            rank = 0,
            availabilityForLocale = { locale ->
                if (locale.startsWith("en")) SpeechAvailability.Available
                else SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale)
            },
        )
        val selector = selector(whisper)

        assertEquals(SpeechAvailability.Available, selector.availability("en-GB"))
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale),
            selector.availability("fr-FR"),
        )
        assertNull(selector.select("fr-FR", ContinuityHint.Utterance))
    }

    @Test
    fun listen_delegatesToSelectedEngine_passingLocaleAndHint() = runTest {
        val events = listOf(TranscriptEvent.Partial("ask not"), TranscriptEvent.Final("ask not what"))
        val whisper = FakeSpeechToText(whisperId, rank = 0, availability = SpeechAvailability.Available, events = events)
        val selector = selector(whisper)

        selector.listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Partial("ask not"), awaitItem())
            assertEquals(TranscriptEvent.Final("ask not what"), awaitItem())
            awaitComplete()
        }
        assertEquals(1, whisper.listens)
        assertEquals("en-US", whisper.lastListenLocale)
        assertEquals(ContinuityHint.Utterance, whisper.lastListenHint)
    }

    @Test
    fun listen_emitsUnavailableError_whenNoEngineAvailable() = runTest {
        val whisper = FakeSpeechToText(whisperId, availability = SpeechAvailability.Unavailable(UnavailableReason.ModelMissing))
        val selector = selector(whisper)

        selector.listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Unavailable), awaitItem())
            awaitComplete()
        }
        // The unavailable engine is never asked to listen.
        assertEquals(0, whisper.listens)
    }

    @Test
    fun supportsContinuous_trueIffSomeEngineDoes() {
        val continuous = FakeSpeechToText(whisperId, supportsContinuous = true)
        val oneShot = FakeSpeechToText(nativeId, supportsContinuous = false)
        assertTrue(SpeechToTextSelector(setOf(continuous, oneShot), noPreference()).supportsContinuous)
        assertEquals(false, SpeechToTextSelector(setOf(oneShot), noPreference()).supportsContinuous)
    }
}
