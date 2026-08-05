package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.CadenceMode
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.Envelope
import com.circuitstitch.deferno.core.network.dto.ChoreDetailDto
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.EventDetailDto
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
 * [Cadence.Unmodelled] degrade — the issue's "round-trips covered by tests" criterion.
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
        assertEquals(Cadence.Daily, habit.recurrence?.cadence)
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
        assertEquals(CadenceMode.Rolling, chore.cadenceMode)
        assertEquals(Cadence.Weekly(listOf("Tue")), chore.recurrence?.cadence)
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
        assertEquals(
            Cadence.Monthly(interval = 2, on = MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri")),
            rule?.cadence,
        )
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
        assertEquals(Cadence.Daily, habit.recurrence?.cadence)

        val chore = ChoreDetailDto(
            id = "c-1",
            orgSlug = "u-e4h2qk",
            title = "trash",
            status = DefStatusWire.Active,
            dateCreated = "2026-05-12T19:52:01Z",
            cadenceMode = "fixed",
            recurrence = RecurrenceDto(type = "monthly"),
        ).toDomain()
        assertEquals(CadenceMode.Fixed, chore.cadenceMode)
        // A bare `{"type":"monthly"}` — no cycle, no anchor — reads as "every month, day unspecified".
        assertEquals(Cadence.Monthly(interval = 1, on = null), chore.recurrence?.cadence)

        // `EventDetailDto.toDomain()` had no test at all before this (only the ItemView.Event extractor
        // did), so the create-response path for an Event — the one that carries the fixed window
        // alongside the rule — was unexercised. It is also the third caller of the widened recurrence
        // mapper, so it must agree with the other two.
        val event = EventDetailDto(
            id = "e-1",
            orgSlug = "u-e4h2qk",
            title = "standup",
            status = DefStatusWire.Active,
            dateCreated = "2026-05-02T15:00:34Z",
            completeBy = "2026-04-18T16:00:00Z",
            endTime = "2026-04-18T17:30:00Z",
            allDay = false,
            recurrence = RecurrenceDto(
                type = "every_n_days",
                n = 3,
                end = RecurrenceEndDto(type = "after_count", n = 10),
            ),
            seriesId = "s-1",
        ).toDomain()
        assertEquals(HydrationState.Full, event.hydration)
        assertEquals(Instant.parse("2026-04-18T16:00:00Z"), event.completeBy)
        assertEquals(Instant.parse("2026-04-18T17:30:00Z"), event.endTime)
        assertEquals(false, event.allDay)
        assertEquals("s-1", event.seriesId)
        assertEquals(
            Recurrence(Cadence.EveryNDays(3), bound = RecurrenceBound.AfterCount(10)),
            event.recurrence,
        )
    }

    /**
     * `cadence_mode` is typed **at this mapper**, and the guard rail is that it must stay a raw `String?`
     * on the DTO (#401). Decoded from real JSON rather than a hand-built DTO precisely because that is
     * the step a `@Serializable enum` would break: `DefernoJson` sets `coerceInputValues = true`
     * (ADR-0005), which rewrites an unrecognised enum token to the property default — the unknown mode
     * would arrive already flattened to `rolling` and no mapper could recover it. So this test fails the
     * moment someone "tidies" the DTO into an enum.
     */
    @Test
    fun anUnrecognisedCadenceModeSurvivesTheDecodeInsteadOfBeingCoercedToTheDefault() {
        fun choreJson(modeKey: String) = DefernoJson.decodeFromString(
            ItemView.serializer(),
            """{"type":"chore","id":"c-1","org_slug":"u-e4h2qk","title":"trash","status":"active",
               "date_created":"2026-05-12T19:52:01Z"$modeKey}""",
        ).asChoreOrNull()!!

        assertEquals(CadenceMode.Unmodelled("drifting"), choreJson(""","cadence_mode":"drifting"""").cadenceMode)
        assertEquals(CadenceMode.Fixed, choreJson(""","cadence_mode":"fixed"""").cadenceMode)
        // An OMITTED key is Rolling, not an unknown — the backend's `#[serde(default)]`. Same for an
        // explicit null, which `coerceInputValues` folds onto the DTO's own `String?` default.
        assertEquals(CadenceMode.Rolling, choreJson("").cadenceMode)
        assertEquals(CadenceMode.Rolling, choreJson(""","cadence_mode":null""").cadenceMode)
    }

    @Test
    fun anUnmodelledRecurrenceTypeDegradesButKeepsItsRawToken() {
        // The degrade itself is unchanged — an unmodelled token must never crash the reader. What is new
        // (#382) is that the token SURVIVES: it used to be replaced by the literal enum name "Unknown"
        // on the way into the cache, which made the original cadence unrecoverable and the item's
        // backup unrestorable.
        assertEquals(
            Recurrence(Cadence.Unmodelled("fortnightly")),
            RecurrenceDto(type = "fortnightly").toDomain(),
        )
        assertNull((null as RecurrenceDto?).toDomain())

        // A rule object with no `type` at all is unmodelled with nothing to preserve — a blank token,
        // which the export side reads as "skip this rule" rather than emitting a tagless body.
        assertEquals(Recurrence(Cadence.Unmodelled("")), RecurrenceDto().toDomain())
    }

    @Test
    fun everySixCadenceTokensCondenseWithTheirOwnParameters() {
        // One assertion per `Cadence` variant the backend defines (backend/src/models/recurrence.rs).
        // `every_n_days` and `custom` are the two that used to collapse to `Unknown` outright; `monthly`
        // and `yearly` kept their name but lost every parameter that gave the rule meaning.
        assertEquals(
            Recurrence(Cadence.Daily),
            RecurrenceDto(type = "daily").toDomain(),
        )
        assertEquals(
            Recurrence(Cadence.EveryNDays(3)),
            RecurrenceDto(type = "every_n_days", n = 3).toDomain(),
        )
        assertEquals(
            Recurrence(Cadence.Weekly(listOf("Mon", "Wed"))),
            RecurrenceDto(type = "weekly", days = listOf("Mon", "Wed")).toDomain(),
        )
        assertEquals(
            Recurrence(Cadence.Monthly(interval = 1, on = MonthlyAnchor.DayOfMonth(15))),
            RecurrenceDto(
                type = "monthly",
                interval = 1,
                on = MonthlyAnchorDto(type = "day_of_month", day = 15),
            ).toDomain(),
        )
        assertEquals(
            Recurrence(Cadence.Yearly(interval = 1, month = 6, day = 14)),
            RecurrenceDto(type = "yearly", interval = 1, month = 6, day = 14).toDomain(),
        )
        assertEquals(
            Recurrence(Cadence.Custom("FREQ=WEEKLY;BYDAY=MO,WE")),
            RecurrenceDto(type = "custom", rrule = "FREQ=WEEKLY;BYDAY=MO,WE").toDomain(),
        )
    }

    @Test
    fun eachCadenceReadsItsOwnNumericWireKeyAndDefaultsAnAbsentOneToOne() {
        // `n` (every_n_days) and `interval` (monthly/yearly) are two different wire keys that can never
        // co-occur; each cadence now names the one it owns, so nothing has to re-route them. Pinned so a
        // future edit cannot quietly start reading the wrong key — or start throwing on a missing one:
        // an absent multiplier is "every one of them", the wire's own default, not a parse failure.
        assertEquals(Cadence.EveryNDays(30), RecurrenceDto(type = "every_n_days", n = 30).toDomain()?.cadence)
        assertEquals(
            Cadence.Monthly(interval = 2),
            RecurrenceDto(type = "monthly", interval = 2).toDomain()?.cadence,
        )
        assertEquals(Cadence.EveryNDays(1), RecurrenceDto(type = "every_n_days").toDomain()?.cadence)
        assertEquals(Cadence.Monthly(interval = 1), RecurrenceDto(type = "monthly").toDomain()?.cadence)
        assertEquals(
            Cadence.Yearly(interval = 1, month = 1, day = 1),
            RecurrenceDto(type = "yearly").toDomain()?.cadence,
        )
        assertEquals(Cadence.Custom(""), RecurrenceDto(type = "custom").toDomain()?.cadence)
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
            (RecurrenceDto(type = "monthly", on = on).toDomain()?.cadence as? Cadence.Monthly)?.on

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
