package com.circuitstitch.deferno.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The floor engine recovers its (transcript, today) from the prompt [Extractor] builds. These pin that
 * recovery to the real `buildContent` format — capture the content the Extractor sends (via the fake
 * engine), then parse it back — so a format change can't silently desync the on-device floor.
 */
class FloorExtractorInputTest {
    @Test
    fun round_trips_through_the_built_extractor_prompt() = runTest {
        val fake = FakeInferenceEngine().apply { enqueue(DraftTasks()) }
        Extractor(fake).extract(
            transcript = Transcript("buy milk tomorrow\nthen call mum"),
            today = LocalDate(2026, 6, 14),
            timeZone = "America/Los_Angeles",
        )

        val parsed = assertNotNull(parseFloorExtractorInput(fake.requests.single().content))
        assertEquals("buy milk tomorrow\nthen call mum", parsed.transcript)
        assertEquals(LocalDate(2026, 6, 14), parsed.today)
    }

    @Test
    fun returns_null_for_non_extractor_content() {
        assertNull(parseFloorExtractorInput("just some prose with no markers"))
    }
}
