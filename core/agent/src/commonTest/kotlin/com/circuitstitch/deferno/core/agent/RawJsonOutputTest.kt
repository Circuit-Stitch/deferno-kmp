package com.circuitstitch.deferno.core.agent

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The raw-text → validated-schema path the on-device engine relies on (ADR-0029 Phase 3): the engine
 * returns free text, so [parse] must survive a code fence, surrounding prose, and braces inside string
 * values — and turn anything that can't decode into the typed [InferenceResult.Failure.MalformedOutput]
 * rather than throwing.
 */
class RawJsonOutputTest {

    private val schema = InferenceSchema(DraftTasks.serializer())

    @Test
    fun parses_a_bare_json_object() {
        val result = schema.parse("""{"drafts":[{"id":"a","title":"Pay rent"}]}""")
        val drafts = assertIs<InferenceResult.Success<DraftTasks>>(result).value.drafts
        assertEquals(listOf("Pay rent"), drafts.map { it.title })
    }

    @Test
    fun strips_a_code_fence_and_preamble() {
        val raw = """
            Sure! Here is the JSON:
            ```json
            {"drafts":[{"id":"a","title":"Email Dana"}]}
            ```
        """.trimIndent()
        val result = schema.parse(raw)
        assertEquals("Email Dana", assertIs<InferenceResult.Success<DraftTasks>>(result).value.drafts.single().title)
    }

    @Test
    fun does_not_truncate_on_a_brace_inside_a_string() {
        val result = schema.parse("""{"drafts":[{"id":"a","title":"Fix the }{ typo"}]}""")
        assertEquals("Fix the }{ typo", assertIs<InferenceResult.Success<DraftTasks>>(result).value.drafts.single().title)
    }

    @Test
    fun reports_malformed_when_there_is_no_json_object() {
        val result = schema.parse("I could not produce any tasks.")
        assertIs<InferenceResult.Failure.MalformedOutput>(result)
    }

    @Test
    fun reports_malformed_when_the_object_does_not_decode() {
        // A balanced object, but `drafts` is the wrong shape for the schema.
        val result = schema.parse("""{"drafts":"not-an-array"}""")
        assertIs<InferenceResult.Failure.MalformedOutput>(result)
    }

    @Test
    fun extractJsonObject_returns_null_without_an_opening_brace() {
        assertNull(extractJsonObject("no json here"))
    }

    // The real on-device model output we adapt to (ADR-0029 Phase 3): a datetime in a date field, a
    // time-of-day, quoted scores, and the string "none" where the schema wants null.
    @Test
    fun coerces_the_models_natural_json_to_the_schema() {
        val raw = """
            {"drafts":[{"id":"1","title":"Email Dana","completeBy":"2026-06-18T16:00:00-07:00",
            "deadlineTimeOfDay":"2026-06-18T16:00","desire":"0.8","productive":"0.9","parentId":"none"}]}
        """.trimIndent()
        val draft = assertIs<InferenceResult.Success<DraftTasks>>(schema.parse(raw)).value.drafts.single()
        assertEquals(LocalDate(2026, 6, 18), draft.completeBy)   // datetime trimmed to the calendar date
        assertEquals(LocalTime(16, 0), draft.deadlineTimeOfDay)  // time taken from after the 'T'
        assertEquals(0.8, draft.desire)                          // quoted score unquoted to a number
        assertNull(draft.parentId)                               // "none" placeholder → null
    }
}
