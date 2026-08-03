package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.CalendarEventDto
import com.circuitstitch.deferno.core.network.dto.ItemKindWire
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The calendar-feed DTO→domain condense-at-edge mapping (ADR-0011, #74). The wire `CalendarEvent`
 * (parsed via [DefernoJson], proving the [kotlinx.serialization.SerialName] keys) condenses its
 * overloaded `TaskStatus` to the clean [WorkingState] (the no-shaming axis), projects its UTC `start`
 * to a local-day [LocalDate] in the supplied zone, and condenses both the free-string `source` and the
 * `kind` the feed has carried since #311 — the routing signal that makes a firing actionable (#380).
 */
class CalendarMapperTest {

    private val utc = TimeZone.of("UTC")

    @Test
    fun wireCalendarEventCondensesToTheCleanDomainType() {
        val json = """
            {
              "id": "ce-1",
              "task_id": "task-7",
              "series_id": "hab-3",
              "kind": "habit",
              "title": "Morning stretch",
              "start": "2026-06-08T09:00:00Z",
              "end": "2026-06-08T09:15:00Z",
              "all_day": false,
              "status": "in-progress",
              "source": "deferno",
              "labels": ["health"]
            }
        """.trimIndent()
        val dto: CalendarEventDto = DefernoJson.decodeFromString(json)

        val item = dto.toDomain(utc)

        assertEquals("ce-1", item.id)
        assertEquals("task-7", item.taskId)
        assertEquals("hab-3", item.seriesId)
        assertEquals("Morning stretch", item.title)
        assertEquals(LocalDate(2026, 6, 8), item.date)
        assertEquals(WorkingState.InProgress, item.status)
        assertEquals(CalendarSource.Deferno, item.source)
        assertEquals(listOf("health"), item.labels)
        // The feed carries the kind (#311), so the firing is routable straight off the row.
        assertEquals(ItemKind.Habit, item.kind)
        assertTrue(item.isActionableOccurrence)
    }

    @Test
    fun everyWireKindTokenCondenses() {
        for ((token, kind) in listOf("task" to ItemKind.Task, "habit" to ItemKind.Habit, "chore" to ItemKind.Chore, "event" to ItemKind.Event)) {
            val dto: CalendarEventDto = DefernoJson.decodeFromString(
                """{"id":"ce","task_id":"t","kind":"$token","title":"x","start":"2026-06-08T00:00:00Z","end":"2026-06-08T00:00:00Z"}""",
            )
            assertEquals(kind, dto.toDomain(utc).kind, "wire token '$token'")
        }
    }

    @Test
    fun anUnknownKindTokenDegradesToNoKind_notAGuessedOne() {
        // ADR-0005: `coerceInputValues` lands an additive token on ItemKindWire.Unknown, and the mapper
        // degrades that to `null` rather than to a member. The kind ROUTES A WRITE (`/habits/…` vs
        // `/chores/…`), so a guess would post to the wrong endpoint; a null renders the row read-only.
        val dto: CalendarEventDto = DefernoJson.decodeFromString(
            """{"id":"ce","task_id":"t","series_id":"s","kind":"sprint","title":"x","start":"2026-06-08T00:00:00Z","end":"2026-06-08T00:00:00Z"}""",
        )
        assertEquals(ItemKindWire.Unknown, dto.kind)
        val item = dto.toDomain(utc)
        assertNull(item.kind)
        assertEquals(false, item.isActionableOccurrence)
    }

    @Test
    fun anAbsentKindDegradesToo_soAnOldCachedPayloadStillDecodes() {
        // `kind` is required on the live wire, but the DTO defaults it: a replayed/older payload must
        // decode rather than throw (the #381 class of failure).
        val dto: CalendarEventDto = DefernoJson.decodeFromString(
            """{"id":"ce","task_id":"t","series_id":"s","title":"x","start":"2026-06-08T00:00:00Z","end":"2026-06-08T00:00:00Z"}""",
        )
        assertNull(dto.toDomain(utc).kind)
    }

    @Test
    fun anExternalRowIsNeverActionable_evenThoughItsKindIsEvent() {
        // The backend stores a synced Google event as an Event-KIND item and instructs clients to gate
        // on `source`. Before #380 `kind` was always null so this was true by accident; now it is by rule.
        val dto: CalendarEventDto = DefernoJson.decodeFromString(
            """{"id":"gcal-9","task_id":"t","series_id":"s","kind":"event","title":"x","start":"2026-06-08T00:00:00Z","end":"2026-06-08T00:00:00Z","source":"google_calendar"}""",
        )
        val item = dto.toDomain(utc)
        assertEquals(ItemKind.Event, item.kind)
        assertEquals(CalendarSource.External, item.source)
        assertEquals(false, item.isActionableOccurrence)
    }

    @Test
    fun startProjectsToTheLocalDayInTheSuppliedZone() {
        // 02:30 UTC on the 9th is still the evening of the 8th in America/Los_Angeles (UTC-7 in June).
        val dto = CalendarEventDto(
            id = "ce-2",
            taskId = "t",
            title = "Late event",
            start = "2026-06-09T02:30:00Z",
            end = "2026-06-09T03:00:00Z",
        )
        val la = dto.toDomain(TimeZone.of("America/Los_Angeles"))
        assertEquals(LocalDate(2026, 6, 8), la.date)

        val utcDay = dto.toDomain(utc)
        assertEquals(LocalDate(2026, 6, 9), utcDay.date)
    }

    @Test
    fun oneOffDatedItemHasNoSeries_andIsADatedTask() {
        val dto = CalendarEventDto(
            id = "ce-3",
            taskId = "task-9",
            seriesId = null,
            title = "File taxes",
            start = "2026-06-08T12:00:00Z",
            end = "2026-06-08T12:00:00Z",
            status = com.circuitstitch.deferno.core.network.dto.TaskStatusWire.Open,
        )
        val item = dto.toDomain(utc)
        assertNull(item.seriesId)
        assertTrue(item.isDatedTask)
        assertEquals(false, item.isActionableOccurrence)
    }

    @Test
    fun additiveStatusAndUnknownSourceDegradeGracefully() {
        // An additive/unknown TaskStatus token coerces to Unknown (ADR-0005) and condenses to Open —
        // a past unfinished firing is never surfaced as "missed" (design-principle #4).
        val unknownStatus = CalendarEventDto(
            id = "ce-4", taskId = "t", title = "x",
            start = "2026-06-08T00:00:00Z", end = "2026-06-08T00:00:00Z",
            status = com.circuitstitch.deferno.core.network.dto.TaskStatusWire.Unknown,
        )
        assertEquals(WorkingState.Open, unknownStatus.toDomain(utc).status)

        // An additive future source degrades to Unknown rather than crashing the reader.
        assertEquals(CalendarSource.External, "google_calendar".toCalendarSource())
        assertEquals(CalendarSource.Unknown, "outlook".toCalendarSource())
    }
}
