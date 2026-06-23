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

    @Test
    fun honoursTheOnDeviceDefaultsTheGraphsNowPass() {
        // #266 (ADR-0027 amendment): on-device inference defaults ON. The Android graph passes the shacl floor
        // and the Apple graph passes Foundation Models as the binding-level default (same `?: default` mechanism
        // the Settings-backed preference uses), so a Brain dump extracts out of the box instead of standing down.
        assertEquals(
            InferenceEngineId.OnDeviceFloor,
            InMemoryInferenceEnginePreference(InferenceEngineId.OnDeviceFloor).selectedEngine(),
        )
        assertEquals(
            InferenceEngineId.OnDeviceFoundationModels,
            InMemoryInferenceEnginePreference(InferenceEngineId.OnDeviceFoundationModels).selectedEngine(),
        )
    }
}
