package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.Envelope
import com.circuitstitch.deferno.core.network.dto.ChoreDetailDto
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.HabitDetailDto
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import com.circuitstitch.deferno.core.network.fixtures.ContractFixtures
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The DTO→domain mapping for the recurring kinds (ADR-0011, #71). Drives the REAL captured
 * `items-sample.json` (which has one habit, one chore, one event element) through the tolerant reader
 * and the `asHabitOrNull`/`asChoreOrNull`/`asEventOrNull` extractors, asserting the condensed domain:
 * the [DefinitionState] light switch, the [com.circuitstitch.deferno.core.model.Recurrence] rule, and
 * each kind's specific fields. Plus the detail-DTO mappers (the create-response shape) and the
 * `Unknown` recurrence-frequency degrade — the issue's "round-trips covered by tests" criterion.
 */
class RecurringItemMapperTest {

    private val items: List<ItemView> = DefernoJson.decodeFromString(
        Envelope.serializer(ListSerializer(ItemView.serializer())),
        ContractFixtures.ALL.getValue("items-sample.json"),
    ).data

    @Test
    fun habitItemCondensesToDomainHabit() {
        val habit = items.firstNotNullOf { it.asHabitOrNull() }

        assertEquals("77dd6a6e-b936-4f61-9807-c3a6b647f9f1", habit.id.value)
        assertEquals(DefinitionState.Active, habit.definitionState)
        assertEquals(RecurrenceFrequency.Daily, habit.recurrence?.frequency)
        assertEquals("b7c21959-c5f6-4087-8ab2-7690c81e463a", habit.seriesId)
        assertEquals(HydrationState.Full, habit.hydration)
        // #289: the recurring mapper forwards the server-derived isBlocker flag (the fixture habit gates a task).
        assertEquals(true, habit.isBlocker)
        assertEquals(false, habit.blocked)
        // A habit element never maps to a chore/event.
        assertNull(items.firstOrNull { it.asHabitOrNull() != null }?.asChoreOrNull())
    }

    @Test
    fun choreItemCondensesWithCadenceAndWeeklyDays() {
        val chore = items.firstNotNullOf { it.asChoreOrNull() }

        assertEquals("47338a14-a07f-4ddf-ad73-f5edc977dab0", chore.id.value)
        assertEquals("rolling", chore.cadenceMode)
        assertEquals(RecurrenceFrequency.Weekly, chore.recurrence?.frequency)
        assertEquals(listOf("Tue"), chore.recurrence?.days)
        assertEquals(DefinitionState.Active, chore.definitionState)
    }

    @Test
    fun eventItemCondensesWithItsFixedWindow() {
        val event = items.firstNotNullOf { it.asEventOrNull() }

        assertEquals("d4f26212-07ac-4ebc-b5d9-fe4649a69a3e", event.id.value)
        assertEquals(false, event.allDay)
        assertEquals(Instant.parse("2026-04-18T17:30:00Z"), event.endTime)
        assertEquals(Instant.parse("2026-04-18T16:00:00Z"), event.completeBy)
        assertEquals(RecurrenceFrequency.Weekly, event.recurrence?.frequency)
    }

    @Test
    fun nonMatchingKindsExtractToNull() {
        // The task element (items[0]) is none of the three recurring kinds.
        val taskView = items.first { it is ItemView.Task }
        assertNull(taskView.asHabitOrNull())
        assertNull(taskView.asChoreOrNull())
        assertNull(taskView.asEventOrNull())
    }

    @Test
    fun detailDtosMapToFullDomainRows() {
        val habit = HabitDetailDto(
            id = "h-1",
            orgSlug = "u-e4h2qk",
            title = "stretch",
            status = DefStatusWire.Active,
            dateCreated = "2026-05-04T01:53:05Z",
            recurrence = RecurrenceDto(type = "daily"),
        ).toDomain()
        assertEquals(HydrationState.Full, habit.hydration)
        assertEquals(RecurrenceFrequency.Daily, habit.recurrence?.frequency)

        val chore = ChoreDetailDto(
            id = "c-1",
            orgSlug = "u-e4h2qk",
            title = "trash",
            status = DefStatusWire.Active,
            dateCreated = "2026-05-12T19:52:01Z",
            cadenceMode = "fixed",
            recurrence = RecurrenceDto(type = "monthly"),
        ).toDomain()
        assertEquals("fixed", chore.cadenceMode)
        assertEquals(RecurrenceFrequency.Monthly, chore.recurrence?.frequency)
    }

    @Test
    fun unknownRecurrenceTypeDegradesAndNullStaysNull() {
        assertEquals(RecurrenceFrequency.Unknown, RecurrenceDto(type = "fortnightly").toDomain()?.frequency)
        assertEquals(RecurrenceFrequency.Yearly, RecurrenceDto(type = "yearly").toDomain()?.frequency)
        assertNull((null as RecurrenceDto?).toDomain())
    }

    @Test
    fun unknownDefStatusDegradesToActive() {
        val habit = HabitDetailDto(
            id = "h-2",
            orgSlug = "u-e4h2qk",
            title = "x",
            status = DefStatusWire.Unknown,
            dateCreated = "2026-05-04T01:53:05Z",
        ).toDomain()
        assertEquals(DefinitionState.Active, habit.definitionState)
    }
}
