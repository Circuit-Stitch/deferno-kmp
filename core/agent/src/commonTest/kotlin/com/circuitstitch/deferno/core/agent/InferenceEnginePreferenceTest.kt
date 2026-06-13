package com.circuitstitch.deferno.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class InferenceEnginePreferenceTest {

    @Test
    fun defaultsToOff() {
        assertEquals(InferenceEngineId.Off, InMemoryInferenceEnginePreference().selectedEngine())
    }

    @Test
    fun roundTripsASetEngine() {
        val pref = InMemoryInferenceEnginePreference()
        pref.setSelectedEngine(InferenceEngineId.DefernoCloud)
        assertEquals(InferenceEngineId.DefernoCloud, pref.selectedEngine())
    }

    @Test
    fun honoursAnInitialEngine() {
        assertEquals(
            InferenceEngineId.DefernoCloud,
            InMemoryInferenceEnginePreference(InferenceEngineId.DefernoCloud).selectedEngine(),
        )
    }
}
