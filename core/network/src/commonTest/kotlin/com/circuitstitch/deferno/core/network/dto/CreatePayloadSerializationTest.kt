package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **write-body** contract for the create payloads (ADR-0016/0011, #71) — the regression for FIX 1
 * (AC #2). `DefernoJson` is configured `explicitNulls = false`, which omits `null` fields but **does
 * not** omit empty strings: a `complete_by = ""` is serialized as `"complete_by":""`, which the server
 * rejects (a date-time is required). So the create flow must supply a non-empty start and pass `null`
 * (not `""`) for absent optionals. These assert the actual serialized JSON the HTTP client POSTs.
 */
class CreatePayloadSerializationTest {

    private val start = "2026-06-08T09:00:00Z"

    @Test
    fun anEmptyCompleteByIsSerializedAsAnEmptyString_documentingWhyTheStartMustBeNonEmpty() {
        // This pins the trap FIX 1 fell into: explicitNulls=false does NOT drop empty strings, so a bare
        // Event create with completeBy="" would POST "complete_by":"" — the server's rejection cause.
        val bad = CreateEventPayload(title = "standup", completeBy = "")
        val json = DefernoJson.encodeToString(bad)
        assertContains(json, "\"complete_by\":\"\"")
    }

    @Test
    fun aValidEventBodyCarriesTheStartAndOmitsNullOptionals() {
        val event = CreateEventPayload(title = "standup", completeBy = start, endTime = null, description = null)
        val json = DefernoJson.encodeToString(event)

        assertContains(json, "\"complete_by\":\"$start\"")
        assertTrue("\"complete_by\":\"\"" !in json, "a non-empty start is present")
        assertFalse(json.contains("end_time"), "a null end_time is omitted, not sent as \"\"")
        assertFalse(json.contains("description"), "a null description is omitted, not sent as \"\"")
    }

    @Test
    fun nullOptionalsAreOmittedAcrossEveryCreatePayload() {
        // The omit-vs-null distinction (the intent-shaped body, ADR-0001) holds for all four kinds: a
        // bare create sends only the required fields, never an empty-string stand-in.
        val task = DefernoJson.encodeToString(CreateTaskPayload(title = "buy milk"))
        assertFalse(task.contains("description"), "a bare Task omits description")
        assertFalse(task.contains("complete_by"), "a bare Task omits complete_by")

        val habit = DefernoJson.encodeToString(
            CreateHabitPayload(title = "stretch", recurrence = RecurrenceDto(type = "daily")),
        )
        assertFalse(habit.contains("description"), "a bare Habit omits description")
        assertFalse(habit.contains("complete_by"), "a bare Habit omits complete_by")
        assertContains(habit, "\"recurrence\"")
    }

    @Test
    fun timeOfDayFieldsSerializeWhenSetAndAreOmittedWhenAbsent() {
        // #348: the deadline/start/end clock rides as snake_case "HH:MM" strings, omitted when null.
        val task = DefernoJson.encodeToString(
            CreateTaskPayload(title = "x", completeBy = "2026-06-20T00:00:00Z", deadlineTimeOfDay = "14:30"),
        )
        assertContains(task, "\"deadline_time_of_day\":\"14:30\"")
        assertFalse(
            DefernoJson.encodeToString(CreateTaskPayload(title = "x")).contains("deadline_time_of_day"),
            "a bare Task omits deadline_time_of_day",
        )

        val event = DefernoJson.encodeToString(
            CreateEventPayload(title = "x", completeBy = start, startTimeOfDay = "09:00", endTimeOfDay = "10:00"),
        )
        assertContains(event, "\"start_time_of_day\":\"09:00\"")
        assertContains(event, "\"end_time_of_day\":\"10:00\"")
        // `all_day` is server-derived now (#348) — the client never sends it.
        assertFalse(event.contains("all_day"), "all_day is derived server-side, not sent on create")
    }
}
