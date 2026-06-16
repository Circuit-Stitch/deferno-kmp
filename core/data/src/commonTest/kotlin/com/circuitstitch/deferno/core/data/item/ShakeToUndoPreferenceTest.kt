package com.circuitstitch.deferno.core.data.item

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The device-local shake-to-undo App setting (ADR-0034 decision 8, #230): the in-memory contract — defaults
 * to on and round-trips a set value. The Settings-backed adapter is exercised through the platform store, not
 * the headless gate (excluded in CoverageConfig), like the other App-setting preferences.
 */
class ShakeToUndoPreferenceTest {

    @Test
    fun defaultsToOn() {
        assertTrue(InMemoryShakeToUndoPreference().enabled(), "shake-to-undo is on by default")
    }

    @Test
    fun honoursTheInitialSeed() {
        assertFalse(InMemoryShakeToUndoPreference(initial = false).enabled())
    }

    @Test
    fun roundTripsASetValue() {
        val pref = InMemoryShakeToUndoPreference()
        pref.setEnabled(false)
        assertFalse(pref.enabled())
        pref.setEnabled(true)
        assertTrue(pref.enabled())
    }
}
