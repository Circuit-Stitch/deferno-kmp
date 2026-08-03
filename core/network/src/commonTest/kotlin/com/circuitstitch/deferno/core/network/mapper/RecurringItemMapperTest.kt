package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.Envelope
import com.circuitstitch.deferno.core.network.dto.ChoreDetailDto
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.HabitDetailDto
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.MonthlyAnchorDto
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import com.circuitstitch.deferno.core.network.dto.RecurrenceEndDto
import com.circuitstitch.deferno.core.network.fixtures.ContractFixtures
import kotlinx.datetime.LocalDate
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
        // #382 — this assertion used to be impossible: the `end` bound had no domain representation at
        // all, so a count-bounded chore was indistinguishable from an open-ended one.
        assertEquals(RecurrenceBound.AfterCount(10), chore.recurrence?.bound)
    }

    @Test
    fun eventItemCondensesWithItsFixedWindow() {
        val event = items.firstNotNullOf { it.asEventOrNull() }

        assertEquals("d4f26212-07ac-4ebc-b5d9-fe4649a69a3e", event.id.value)
        assertEquals(false, event.allDay)
        assertEquals(Instant.parse("2026-04-18T17:30:00Z"), event.endTime)
        assertEquals(Instant.parse("2026-04-18T16:00:00Z"), event.completeBy)
        // #382 — the captured event is "the last Friday of every other month, until 2027-01-31". Before
        // the widening the domain could say only `Monthly`, and everything that makes the rule mean
        // anything — the cycle, the anchor, the bound — was discarded at this mapper.
        val rule = event.recurrence
        assertEquals(RecurrenceFrequency.Monthly, rule?.frequency)
        assertEquals(2, rule?.interval)
        assertEquals(MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri"), rule?.monthlyAnchor)
        assertEquals(RecurrenceBound.OnDate(LocalDate(2027, 1, 31)), rule?.bound)
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
    fun unknownRecurrenceTypeDegradesButKeepsItsRawToken() {
        // The degrade itself is unchanged — an unmodelled token must never crash the reader. What is new
        // (#382) is that the token SURVIVES: it used to be replaced by the literal enum name "Unknown"
        // on the way into the cache, which made the original cadence unrecoverable and the item's
        // backup unrestorable. `rawType` is populated ONLY on the Unknown arm.
        val unknown = RecurrenceDto(type = "fortnightly").toDomain()
        assertEquals(RecurrenceFrequency.Unknown, unknown?.frequency)
        assertEquals("fortnightly", unknown?.rawType)

        assertNull(RecurrenceDto(type = "yearly").toDomain()?.rawType)
        assertNull((null as RecurrenceDto?).toDomain())

        // A rule object with no `type` at all is Unknown with nothing to preserve.
        val typeless = RecurrenceDto().toDomain()
        assertEquals(RecurrenceFrequency.Unknown, typeless?.frequency)
        assertNull(typeless?.rawType)
        assertEquals(RecurrenceBound.Never, typeless?.bound)
    }

    @Test
    fun everySixCadenceTokensCondenseWithTheirOwnParameters() {
        // One assertion per `Cadence` variant the backend defines (backend/src/models/recurrence.rs).
        // `every_n_days` and `custom` are the two that used to collapse to `Unknown` outright; `monthly`
        // and `yearly` kept their name but lost every parameter that gave the rule meaning.
        assertEquals(
            Recurrence(RecurrenceFrequency.Daily),
            RecurrenceDto(type = "daily").toDomain(),
        )
        assertEquals(
            Recurrence(RecurrenceFrequency.EveryNDays, interval = 3),
            RecurrenceDto(type = "every_n_days", n = 3).toDomain(),
        )
        assertEquals(
            Recurrence(RecurrenceFrequency.Weekly, days = listOf("Mon", "Wed")),
            RecurrenceDto(type = "weekly", days = listOf("Mon", "Wed")).toDomain(),
        )
        assertEquals(
            Recurrence(
                RecurrenceFrequency.Monthly,
                interval = 1,
                monthlyAnchor = MonthlyAnchor.DayOfMonth(15),
            ),
            RecurrenceDto(
                type = "monthly",
                interval = 1,
                on = MonthlyAnchorDto(type = "day_of_month", day = 15),
            ).toDomain(),
        )
        assertEquals(
            Recurrence(RecurrenceFrequency.Yearly, interval = 1, month = 6, day = 14),
            RecurrenceDto(type = "yearly", interval = 1, month = 6, day = 14).toDomain(),
        )
        assertEquals(
            Recurrence(RecurrenceFrequency.Custom, rrule = "FREQ=WEEKLY;BYDAY=MO,WE"),
            RecurrenceDto(type = "custom", rrule = "FREQ=WEEKLY;BYDAY=MO,WE").toDomain(),
        )
    }

    @Test
    fun theCycleMultiplierReadsWhicheverWireKeyTheCadenceUses() {
        // `n` (every_n_days) and `interval` (monthly/yearly) are the same domain concept — the cycle
        // multiplier — and can never co-occur, so one field reads both. Pinned so a future edit cannot
        // quietly start preferring one and dropping the other.
        assertEquals(30, RecurrenceDto(type = "every_n_days", n = 30).toDomain()?.interval)
        assertEquals(2, RecurrenceDto(type = "monthly", interval = 2).toDomain()?.interval)
        assertNull(RecurrenceDto(type = "daily").toDomain()?.interval)
    }

    @Test
    fun allThreeEndBoundsCondenseAndAnAbsentEndIsNever() {
        // An ABSENT `end` is the only encoding of "never" the server emits (its Serialize skips the key),
        // so absent must mean Never — not "unknown".
        assertEquals(RecurrenceBound.Never, RecurrenceDto(type = "daily").toDomain()?.bound)
        // …but its Deserialize accepts an explicit {"type":"never"}, so the reader tolerates one.
        assertEquals(
            RecurrenceBound.Never,
            RecurrenceDto(type = "daily", end = RecurrenceEndDto(type = "never")).toDomain()?.bound,
        )
        assertEquals(
            RecurrenceBound.OnDate(LocalDate(2027, 1, 31)),
            RecurrenceDto(type = "daily", end = RecurrenceEndDto(type = "on_date", date = "2027-01-31"))
                .toDomain()?.bound,
        )
        assertEquals(
            RecurrenceBound.AfterCount(10),
            RecurrenceDto(type = "daily", end = RecurrenceEndDto(type = "after_count", n = 10))
                .toDomain()?.bound,
        )
    }

    @Test
    fun aMalformedEndBoundDegradesToNeverRatherThanThrowing() {
        // Tolerant degradation, not strictness: an over-strict bound parse inside the `/items` decode
        // would resurrect exactly the whole-snapshot stall of #381. Every unusable shape → Never.
        fun boundOf(end: RecurrenceEndDto) = RecurrenceDto(type = "daily", end = end).toDomain()?.bound

        assertEquals(RecurrenceBound.Never, boundOf(RecurrenceEndDto(type = "until_further_notice")))
        assertEquals(RecurrenceBound.Never, boundOf(RecurrenceEndDto()), "no type at all")
        assertEquals(RecurrenceBound.Never, boundOf(RecurrenceEndDto(type = "on_date")), "no date")
        assertEquals(
            RecurrenceBound.Never,
            boundOf(RecurrenceEndDto(type = "on_date", date = "31/01/2027")),
            "an unparseable date",
        )
        assertEquals(RecurrenceBound.Never, boundOf(RecurrenceEndDto(type = "after_count")), "no count")
    }

    @Test
    fun bothMonthlyAnchorsCondenseAndAHalfPopulatedOneDegradesToNull() {
        fun anchorOf(on: MonthlyAnchorDto?) =
            RecurrenceDto(type = "monthly", on = on).toDomain()?.monthlyAnchor

        assertEquals(MonthlyAnchor.DayOfMonth(15), anchorOf(MonthlyAnchorDto("day_of_month", day = 15)))
        // nth is an i8: -1 means "the LAST <weekday> of the month" (RFC 5545 BYDAY=-1FR).
        assertEquals(
            MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri"),
            anchorOf(MonthlyAnchorDto("nth_weekday", nth = -1, weekday = "Fri")),
        )
        assertEquals(
            MonthlyAnchor.NthWeekday(nth = 2, weekday = "Wed"),
            anchorOf(MonthlyAnchorDto("nth_weekday", nth = 2, weekday = "Wed")),
        )

        // A monthly rule with no usable anchor is still a usable monthly rule — it just cannot say which
        // day. Degrading to null beats throwing (see the #381 stall class) and beats inventing a day.
        assertNull(anchorOf(null), "absent")
        assertNull(anchorOf(MonthlyAnchorDto("last_business_day")), "an unknown anchor token")
        assertNull(anchorOf(MonthlyAnchorDto("day_of_month")), "day_of_month with no day")
        assertNull(anchorOf(MonthlyAnchorDto("nth_weekday", nth = 2)), "nth_weekday with no weekday")
        assertNull(anchorOf(MonthlyAnchorDto("nth_weekday", weekday = "Wed")), "nth_weekday with no nth")
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
