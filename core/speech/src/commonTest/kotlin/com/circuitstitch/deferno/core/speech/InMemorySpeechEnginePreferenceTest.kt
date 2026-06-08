package com.circuitstitch.deferno.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals

/** The in-memory engine preference (ADR-0018): defaults to Whisper and round-trips a chosen engine. */
class InMemorySpeechEnginePreferenceTest {

    @Test
    fun defaultsToWhisper() {
        assertEquals(SpeechEngineId.Whisper, InMemorySpeechEnginePreference().preferredEngine())
    }

    @Test
    fun honoursInitialChoice() {
        val initial = SpeechEngineId("android-mlkit")
        assertEquals(initial, InMemorySpeechEnginePreference(initial).preferredEngine())
    }

    @Test
    fun setPreferredEngine_roundTrips() {
        val preference = InMemorySpeechEnginePreference()
        val chosen = SpeechEngineId("android-mlkit")
        preference.setPreferredEngine(chosen)
        assertEquals(chosen, preference.preferredEngine())
    }
}
