package com.circuitstitch.deferno.core.data.braindump

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The device-local "keep brain-dump recordings" choice (#211): the in-memory preference defaults to on and
 * round-trips a set value, and the recording placeholder id is keyed by the recording's instant. Measured
 * (commonTest) — the platform Settings adapter ([SettingsKeepBrainDumpRecordingsPreference]) is
 * coverage-excluded, exercised through the real platform store like [SettingsStorageProviderPreference].
 */
class KeepBrainDumpRecordingsPreferenceTest {

    @Test
    fun defaultsToOn() {
        assertTrue(InMemoryKeepBrainDumpRecordingsPreference().enabled())
    }

    @Test
    fun seedCanStartOff() {
        assertFalse(InMemoryKeepBrainDumpRecordingsPreference(initial = false).enabled())
    }

    @Test
    fun roundTripsASetValue() {
        val pref = InMemoryKeepBrainDumpRecordingsPreference()
        pref.setEnabled(false)
        assertFalse(pref.enabled())
        pref.setEnabled(true)
        assertTrue(pref.enabled())
    }

    @Test
    fun placeholderIdIsKeyedByTheRecordingInstant() {
        val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        assertEquals("braindump-audio-1700000000000", brainDumpRecordingPlaceholderId(at))
    }
}
