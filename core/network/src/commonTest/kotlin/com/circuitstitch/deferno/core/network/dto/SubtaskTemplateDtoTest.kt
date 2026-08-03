package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The wire contract for `subtask_template` (#381) — the array a recurring definition clones into fresh
 * subtask Tasks when an occurrence materializes (backend `subtask_template.rs`).
 *
 * The element is an **object** (`{id, title, description}`), and the DTO used to declare
 * `List<String>`. `DefernoJson` is `isLenient = false` and `coerceInputValues = true` — neither coerces
 * an object into a String — so the first populated template threw a `SerializationException` inside the
 * **whole `/items` decode**, which becomes `ApiError.Transport` → `RemoteSnapshot.Unavailable` → an
 * early-returning `ItemSync.refresh()`. One habit with a template therefore froze the cache for **all
 * four kinds, Tasks included**, presenting as ordinary offline behaviour with no error surfaced. These
 * pin the shape, the absent-`description` default, and that a populated template no longer poisons the
 * sibling elements of the union.
 */
class SubtaskTemplateDtoTest {

    @Test
    fun aTemplateEntryDecodesAsAnObjectWithItsThreeFields() {
        val entry = DefernoJson.decodeFromString(
            SubtaskTemplateDto.serializer(),
            """{"id":"t-1","title":"warm up","description":"five minutes"}""",
        )

        assertEquals("t-1", entry.id)
        assertEquals("warm up", entry.title)
        assertEquals("five minutes", entry.description)
    }

    @Test
    fun anAbsentDescriptionDefaultsToEmptyRatherThanThrowing() {
        // The backend declares `#[serde(default, skip_serializing_if = "String::is_empty")]`, so the key
        // is ABSENT whenever the description is empty and on every legacy row. A non-defaulted field here
        // would trade one decode crash for another — this is the assertion that stops that.
        val entry = DefernoJson.decodeFromString(
            SubtaskTemplateDto.serializer(),
            """{"id":"t-2","title":"cool down"}""",
        )

        assertEquals("", entry.description)
    }

    @Test
    fun anEmptyTemplateArrayStillDecodesToAnEmptyList() {
        // Every live recurring row carries `"subtask_template": []` today — the case that kept the bug
        // latent. It must keep decoding to an empty list, not to a list of one empty entry.
        val entries = DefernoJson.decodeFromString(
            ListSerializer(SubtaskTemplateDto.serializer()),
            "[]",
        )

        assertEquals(emptyList(), entries)
    }

    @Test
    fun aPopulatedTemplateNoLongerStallsTheWholeItemsUnionDecode() {
        // THE #381 regression, at the granularity that actually broke: the `/items` list is decoded in a
        // SINGLE call, so one un-decodable habit element failed the entire snapshot — Tasks included.
        // Decoding a mixed list proves the sibling task element survives a populated template.
        val json = """
            [
              {"type":"task","id":"t-1","org_slug":"u-x","title":"buy milk","date_created":"2026-05-04T01:53:05Z"},
              {"type":"habit","id":"h-1","org_slug":"u-x","title":"stretch","date_created":"2026-05-04T01:53:05Z",
               "subtask_template":[{"id":"s-1","title":"warm up","description":"5m"},{"id":"s-2","title":"cool down"}]}
            ]
        """.trimIndent()

        val items = DefernoJson.decodeFromString(ListSerializer(ItemView.serializer()), json)

        assertEquals(2, items.size)
        assertIs<ItemView.Task>(items[0])
        val habit = assertIs<ItemView.Habit>(items[1])
        assertEquals(listOf("warm up", "cool down"), habit.subtaskTemplate.map { it.title })
        assertEquals(listOf("5m", ""), habit.subtaskTemplate.map { it.description })
    }

    @Test
    fun everyRecurringKindCarriesTheTemplateOnBothTheUnionAndTheDetailShape() {
        // ADR-0011 drift: the three *Detail* DTOs omitted `subtask_template` entirely, so
        // `ignoreUnknownKeys` dropped it silently on `GET /habits/{id}` — not a crash, but the detail DTO
        // claims to be the faithful full single-item shape. Chore and Event carry it on both shapes too.
        val template = """[{"id":"s-1","title":"warm up"}]"""

        val chore = DefernoJson.decodeFromString(
            ItemView.serializer(),
            """{"type":"chore","id":"c-1","org_slug":"u-x","title":"trash","date_created":"2026-05-04T01:53:05Z","subtask_template":$template}""",
        )
        assertEquals("warm up", assertIs<ItemView.Chore>(chore).subtaskTemplate.single().title)

        val event = DefernoJson.decodeFromString(
            ItemView.serializer(),
            """{"type":"event","id":"e-1","org_slug":"u-x","title":"standup","date_created":"2026-05-04T01:53:05Z","subtask_template":$template}""",
        )
        assertEquals("warm up", assertIs<ItemView.Event>(event).subtaskTemplate.single().title)

        val habitDetail = DefernoJson.decodeFromString(
            HabitDetailDto.serializer(),
            """{"id":"h-1","org_slug":"u-x","title":"stretch","date_created":"2026-05-04T01:53:05Z","subtask_template":$template}""",
        )
        assertEquals("s-1", habitDetail.subtaskTemplate.single().id)

        val choreDetail = DefernoJson.decodeFromString(
            ChoreDetailDto.serializer(),
            """{"id":"c-1","org_slug":"u-x","title":"trash","date_created":"2026-05-04T01:53:05Z","subtask_template":$template}""",
        )
        assertEquals("s-1", choreDetail.subtaskTemplate.single().id)

        val eventDetail = DefernoJson.decodeFromString(
            EventDetailDto.serializer(),
            """{"id":"e-1","org_slug":"u-x","title":"standup","date_created":"2026-05-04T01:53:05Z","subtask_template":$template}""",
        )
        assertEquals("s-1", eventDetail.subtaskTemplate.single().id)
    }

    @Test
    fun anEmptyDescriptionIsOmittedOnEncodeMirroringTheBackendSkipRule() {
        // The Backup file (ADR-0041) re-emits [ItemView] through this same DTO, so the write side matters:
        // `encodeDefaults = false` drops an empty `description`, exactly as the backend's
        // `skip_serializing_if = "String::is_empty"` does — and a re-import reads it back as "".
        val bare = DefernoJson.encodeToString(SubtaskTemplateDto(id = "s-1", title = "warm up"))
        assertFalse(bare.contains("description"), "an empty description is omitted, not sent as \"\"")

        val described = DefernoJson.encodeToString(
            SubtaskTemplateDto(id = "s-1", title = "warm up", description = "5m"),
        )
        assertTrue(described.contains("\"description\":\"5m\""), "a non-empty description is emitted")
    }
}
