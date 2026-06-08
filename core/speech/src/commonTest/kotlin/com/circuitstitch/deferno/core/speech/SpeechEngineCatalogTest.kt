package com.circuitstitch.deferno.core.speech

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DefaultSpeechEngineCatalog] (#93, ADR-0018): the device-local speech-engine read model the Settings
 * Destination renders. It lists [SpeechEngineId.Automatic] first, then each **real** registered engine in
 * descending rank with its live availability (the [UnavailableSpeechToText] floor excluded), and
 * round-trips the choice through the [SpeechEnginePreference]. Measured (the enumeration/ordering/exclusion
 * and the preference round-trip are real logic; only the platform-store preference impl is excluded).
 */
class SpeechEngineCatalogTest {

    private val whisperId = SpeechEngineId.Whisper
    private val nativeId = SpeechEngineId("native-fast-path")

    private fun catalog(
        vararg engines: SpeechToText,
        preference: SpeechEnginePreference = InMemorySpeechEnginePreference(),
    ) = DefaultSpeechEngineCatalog(engines.toSet(), preference)

    @Test
    fun options_leadWithAutomatic_thenRealEnginesByRankDescending() = runTest {
        val whisper = FakeSpeechToText(whisperId, rank = 0, availability = SpeechAvailability.Available)
        val native = FakeSpeechToText(nativeId, rank = 10, availability = SpeechAvailability.Available)

        val options = catalog(whisper, native).options("en-US")

        // Automatic always leads; the real engines follow in descending rank (the selector's order).
        assertEquals(
            listOf(SpeechEngineId.Automatic, nativeId, whisperId),
            options.map { it.id },
        )
    }

    @Test
    fun options_carryEachEnginesLiveAvailability() = runTest {
        val whisper = FakeSpeechToText(whisperId, rank = 0, availability = SpeechAvailability.Available)
        val native = FakeSpeechToText(
            nativeId,
            rank = 10,
            availability = SpeechAvailability.Unavailable(UnavailableReason.ModelMissing),
        )

        val byId = catalog(whisper, native).options("en-US").associate { it.id to it.availability }

        // Automatic is a strategy, not an engine — always Available.
        assertEquals(SpeechAvailability.Available, byId[SpeechEngineId.Automatic])
        assertEquals(SpeechAvailability.Available, byId[whisperId])
        assertEquals(SpeechAvailability.Unavailable(UnavailableReason.ModelMissing), byId[nativeId])
    }

    @Test
    fun options_queryAvailabilityForTheGivenLocale() = runTest {
        // An English-only engine: Available for en-*, Unavailable otherwise (the v1 whisper behaviour).
        val whisper = FakeSpeechToText(
            whisperId,
            rank = 0,
            availabilityForLocale = { locale ->
                if (locale.startsWith("en")) SpeechAvailability.Available
                else SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale)
            },
        )

        val french = catalog(whisper).options("fr-FR").first { it.id == whisperId }
        assertEquals(SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale), french.availability)
    }

    @Test
    fun options_excludeTheUnavailableFloor_soNoRealEngineYieldsOnlyAutomatic() = runTest {
        // Desktop/iOS pre-#94/#95: the only registered engine is the always-unavailable floor.
        val options = catalog(UnavailableSpeechToText).options("en-US")

        // The floor is not a user choice — only the Automatic strategy remains, so the View hides the row.
        assertEquals(listOf(SpeechEngineId.Automatic), options.map { it.id })
    }

    @Test
    fun selected_defaultsToWhisper_whenNonePersisted() {
        val whisper = FakeSpeechToText(whisperId, rank = 0)
        assertEquals(SpeechEngineId.Whisper, catalog(whisper).selected())
    }

    @Test
    fun select_persistsThroughThePreference_andIsReadBack() {
        val whisper = FakeSpeechToText(whisperId, rank = 0)
        val preference = InMemorySpeechEnginePreference()
        val catalog = catalog(whisper, preference = preference)

        catalog.select(SpeechEngineId.Automatic)

        assertEquals(SpeechEngineId.Automatic, catalog.selected())
        // The same device-local preference the selector reads — they never diverge.
        assertEquals(SpeechEngineId.Automatic, preference.preferredEngine())
    }

    @Test
    fun options_withNoEnginesAtAll_isJustAutomatic() = runTest {
        val options = catalog().options("en-US")
        assertEquals(listOf(SpeechEngineId.Automatic), options.map { it.id })
        assertTrue(options.single().availability == SpeechAvailability.Available)
    }

    @Test
    fun emptyCatalog_isInert_onlyAutomatic_defaultWhisper_selectIsNoOp() = runTest {
        // The shared inert default (analogue of UnavailableSpeechToText): no real engine, default Whisper.
        assertEquals(listOf(SpeechEngineId.Automatic), EmptySpeechEngineCatalog.options("en-US").map { it.id })
        assertEquals(SpeechEngineId.Whisper, EmptySpeechEngineCatalog.selected())
        // select is inert (no store) — selected() is unchanged afterward.
        EmptySpeechEngineCatalog.select(SpeechEngineId.Automatic)
        assertEquals(SpeechEngineId.Whisper, EmptySpeechEngineCatalog.selected())
    }
}
