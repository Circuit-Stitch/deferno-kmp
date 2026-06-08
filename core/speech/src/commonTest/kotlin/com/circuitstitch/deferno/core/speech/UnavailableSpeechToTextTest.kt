package com.circuitstitch.deferno.core.speech

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The always-unavailable engine floor (ADR-0018): never available, never silently succeeds. */
class UnavailableSpeechToTextTest {

    @Test
    fun identity_isTheUnavailableFloor() {
        assertEquals(SpeechEngineId.Unavailable, UnavailableSpeechToText.id)
        assertEquals(Int.MIN_VALUE, UnavailableSpeechToText.rank)
        assertFalse(UnavailableSpeechToText.supportsContinuous)
    }

    @Test
    fun availability_isAlwaysUnavailable() = runTest {
        assertEquals(
            SpeechAvailability.Unavailable(UnavailableReason.NoEngine),
            UnavailableSpeechToText.availability("en-US"),
        )
    }

    @Test
    fun listen_emitsSingleUnavailableError_thenCompletes() = runTest {
        UnavailableSpeechToText.listen("en-US", ContinuityHint.Utterance).test {
            assertEquals(TranscriptEvent.Error(SpeechError.Unavailable), awaitItem())
            awaitComplete()
        }
    }
}
