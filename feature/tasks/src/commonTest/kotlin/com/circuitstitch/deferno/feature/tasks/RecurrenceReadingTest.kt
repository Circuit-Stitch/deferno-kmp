package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceCursor
import com.circuitstitch.deferno.core.model.RelativeDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The **one** normalisation every platform's recurring-row subtitle is built from (#384) — the rules that
 * used to be written three times (the Compose renderer, plus each Apple app's own `Bridge.kt`) and are now
 * written once. That is why these assertions live in the slice's Compose-free logic module rather than
 * beside a renderer: this is the only file that can drift, and being a feature slice it is measured by
 * the Kover gate, which `app/iosApp`/`app/macosApp` never were.
 *
 * Two halves. [RecurrenceReading] is the typed shape Compose renders from; [RecurrenceLineTokens] is the
 * single value that crosses to SwiftUI. The token half is asserted literally, because a token is a
 * *contract between two languages* — renaming an arm compiles cleanly on both sides and simply renders
 * the wrong phrase.
 */
class RecurrenceReadingTest {

    /** Pinned "today"; every fixture instant is read against it in [zone], never the wall clock. */
    private val today = LocalDate(2026, 6, 15)

    /** UTC so an `Instant` → day resolution can't drift with the machine's zone. */
    private val zone = TimeZone.UTC

    private fun item(
        cadence: Cadence,
        bound: RecurrenceBound = RecurrenceBound.Never,
        cursorAt: Instant? = Instant.parse("2026-06-16T12:00:00Z"),
        state: DefinitionState = DefinitionState.Active,
    ) = Item(
        id = "h-1",
        kind = ItemKind.Habit,
        title = "Water the plants",
        definitionState = state,
        recurrence = Recurrence(cadence, bound),
        recurrenceCursorAt = cursorAt,
    )

    private fun readingOf(cadence: Cadence, bound: RecurrenceBound = RecurrenceBound.Never) =
        requireNotNull(item(cadence, bound).recurrenceReading(zone, today))

    private fun cadenceOf(cadence: Cadence): CadenceReading = readingOf(cadence).cadence

    private fun tokensOf(cadence: Cadence, bound: RecurrenceBound = RecurrenceBound.Never) =
        requireNotNull(recurrenceLineTokens(item(cadence, bound), zone, today))

    // --- the rule is the discriminator, the cursor only the value ---

    /** A Task carries no rule, so there is nothing to read — and a cursor alone is never enough. */
    @Test
    fun anItemWithNoRuleHasNoReadingAtAll() {
        assertNull(
            Item(id = "t-1", kind = ItemKind.Task, title = "Plan the launch").recurrenceReading(zone, today),
        )
        assertNull(
            Item(
                id = "h-2",
                kind = ItemKind.Habit,
                title = "Morning run",
                recurrenceCursorAt = Instant.parse("2026-06-16T12:00:00Z"),
            ).recurrenceReading(zone, today),
            "a recurring kind whose rule did not survive the wire says nothing either",
        )
    }

    // --- RENDERER RULE 1: the EveryNDays fold ---

    /** A stride of one IS "Daily", and several locales have no grammatical "Every 1 day" to fall back on. */
    @Test
    fun aStrideOfOneOrLessFoldsIntoDaily() {
        assertEquals(CadenceReading.Daily, cadenceOf(Cadence.EveryNDays(1)))
        assertEquals(CadenceReading.Daily, cadenceOf(Cadence.EveryNDays(0)))
        assertEquals(CadenceReading.Daily, cadenceOf(Cadence.EveryNDays(-3)))
        assertEquals(CadenceReading.EveryNDays(2), cadenceOf(Cadence.EveryNDays(2)))
    }

    // --- RENDERER RULE 2: the interval floor ---

    /**
     * A non-positive interval is floored so no renderer can print "Every 0 months". An interval of exactly
     * 1 passes through **unchanged**: the catalog plurals' `one` arm is what drops the numeral, and doing
     * it here as well would be the same rule written twice.
     */
    @Test
    fun intervalsAreFlooredAtOneButOneItselfIsLeftAlone() {
        assertEquals(CadenceReading.Monthly(1), cadenceOf(Cadence.Monthly(0)))
        assertEquals(CadenceReading.Monthly(1), cadenceOf(Cadence.Monthly(1)))
        assertEquals(CadenceReading.Monthly(3), cadenceOf(Cadence.Monthly(3)))
        assertEquals(CadenceReading.Yearly(1), cadenceOf(Cadence.Yearly(-1, 6, 14)))
        assertEquals(CadenceReading.Yearly(2), cadenceOf(Cadence.Yearly(2, 6, 14)))
    }

    // --- RENDERER RULE 3: weekday canonicalisation ---

    /** The row states WHICH days a rule fires on, not the order the server happened to serialize them in. */
    @Test
    fun weekdaysBecomeIsoNumbersInWeekOrderWithoutDuplicates() {
        assertEquals(
            CadenceReading.Weekly(listOf(1, 3, 6)),
            cadenceOf(Cadence.Weekly(listOf("Sat", "wed", "Mon", "Wed"))),
        )
        assertEquals(
            CadenceReading.Weekly(listOf(1, 7)),
            cadenceOf(Cadence.Weekly(listOf("Sun", "Mon"))),
            "ISO order is Monday-first, so Sunday sorts LAST — both renderers index off these numbers",
        )
    }

    /**
     * An unplaceable token is **dropped**, not thrown on: a rule this build cannot fully read still
     * round-trips the cache verbatim (#382), so it must degrade to a shorter list — and if every token
     * goes, to the empty list both renderers show as the bare "Weekly".
     */
    @Test
    fun anUnknownWeekdayTokenIsDroppedRatherThanThrownOn() {
        assertEquals(
            CadenceReading.Weekly(listOf(1, 5)),
            cadenceOf(Cadence.Weekly(listOf("Mon", "Blursday", "Fri"))),
        )
        assertEquals(CadenceReading.Weekly(emptyList()), cadenceOf(Cadence.Weekly(listOf("Cinqui", "Sexta"))))
        assertEquals(CadenceReading.Weekly(emptyList()), cadenceOf(Cadence.Weekly(emptyList())))
    }

    // --- the wire parameters that deliberately never reach a row ---

    /**
     * `Monthly.on`, `Yearly.month`/`day` and `Custom.rrule` are dropped HERE, once, rather than in each of
     * four renderers — which is what stops one platform quietly growing a paraphrase the other three lack.
     * The first needs ordinal+weekday grammar no locale here has a key family for; the last is machine text.
     */
    @Test
    fun theUnrenderableWireParametersAreDroppedForEveryPlatformAtOnce() {
        assertEquals(CadenceReading.Monthly(2), cadenceOf(Cadence.Monthly(2, MonthlyAnchor.NthWeekday(2, "Tue"))))
        assertEquals(CadenceReading.Monthly(1), cadenceOf(Cadence.Monthly(1, MonthlyAnchor.DayOfMonth(15))))
        assertEquals(CadenceReading.Yearly(1), cadenceOf(Cadence.Yearly(1, 6, 14)))
        assertEquals(CadenceReading.Custom, cadenceOf(Cadence.Custom("FREQ=DAILY;COUNT=5")))
        assertEquals(CadenceReading.Unspecified, cadenceOf(Cadence.Unmodelled("lunar_phase")))
    }

    // --- the bound ---

    /** `Never` is the open-ended default: it nulls out here so no renderer has to special-case it. */
    @Test
    fun anOpenEndedRuleCarriesNoBoundAtAll() {
        assertNull(readingOf(Cadence.Daily).bound)
        assertEquals(RecurrenceBound.AfterCount(10), readingOf(Cadence.Daily, RecurrenceBound.AfterCount(10)).bound)
    }

    // --- the cursor, and the zone it is read in ---

    @Test
    fun theCursorReadsAgainstTheSuppliedDay() {
        assertEquals(RecurrenceCursor.DueOn(RelativeDay.Tomorrow), readingOf(Cadence.Daily).cursor)
        assertEquals(
            RecurrenceCursor.Exhausted,
            requireNotNull(item(Cadence.Daily, cursorAt = null).recurrenceReading(zone, today)).cursor,
        )
        assertEquals(
            RecurrenceCursor.NoCursor,
            requireNotNull(
                item(Cadence.Daily, state = DefinitionState.Archived).recurrenceReading(zone, today),
            ).cursor,
            "an archived definition keeps its cadence but has no next",
        )
    }

    /**
     * The [zone] genuinely reaches the instant→day resolution rather than the device's being used. The
     * fixture sits half an hour after UTC midnight, which is a *different calendar day* in Niue (UTC-11)
     * than in UTC or Kiritimati (UTC+14) — so a zone that was being ignored would read all three alike.
     *
     * This is the reading half of the coupling `recurrenceReading` enforces on its signature: `today`
     * defaults **from** `zone`, so supplying only a zone — exactly the call shape #392 will use once an
     * account zone exists — can never leave "today" resolved in the device's.
     */
    @Test
    fun theSuppliedZoneDecidesWhichCalendarDayTheCursorLandsOn() {
        val fixture = item(Cadence.Daily, cursorAt = Instant.parse("2026-06-16T00:30:00Z"))
        val onTheSixteenth = LocalDate(2026, 6, 16)

        fun cursorIn(id: String) =
            requireNotNull(fixture.recurrenceReading(TimeZone.of(id), onTheSixteenth)).cursor

        assertEquals(RecurrenceCursor.DueOn(RelativeDay.Today), cursorIn("UTC"))
        assertEquals(RecurrenceCursor.DueOn(RelativeDay.Today), cursorIn("Pacific/Kiritimati"))
        assertEquals(
            RecurrenceCursor.DueOn(RelativeDay.Yesterday),
            cursorIn("Pacific/Niue"),
            "UTC-11 is still on the 15th at 00:30Z — the zone must reach the day resolution",
        )
    }

    // --- the single value that crosses to SwiftUI ---

    /** The tokens Swift switches on, asserted literally — see the class KDoc for why. */
    @Test
    fun theSwiftTokensAreTheOnesTheCatalogKeysAreNamedFor() {
        assertEquals("DAILY", tokensOf(Cadence.Daily).cadence)
        assertEquals("EVERY_N_DAYS", tokensOf(Cadence.EveryNDays(3)).cadence)
        assertEquals("WEEKLY", tokensOf(Cadence.Weekly(listOf("Mon"))).cadence)
        assertEquals("MONTHLY", tokensOf(Cadence.Monthly(1)).cadence)
        assertEquals("YEARLY", tokensOf(Cadence.Yearly(1, 6, 14)).cadence)
        assertEquals("CUSTOM", tokensOf(Cadence.Custom("FREQ=DAILY")).cadence)
        assertEquals("UNSPECIFIED", tokensOf(Cadence.Unmodelled("lunar_phase")).cadence)
        assertEquals(
            "DAILY",
            tokensOf(Cadence.EveryNDays(1)).cadence,
            "the fold applies below the bridge — neither Swift twin gets to re-decide it",
        )
    }

    /**
     * Every count is **null** on the arms that carry no number — the whole reason this crosses as one value
     * type rather than eight paired getters. The shape it replaced encoded "no number" as a returned `0`,
     * including a `0` epoch-day that decodes to a real 1970-01-01, an invariant held by a comment across a
     * language boundary with no test. Swift now cannot read a count without unwrapping it first.
     */
    @Test
    fun anArmThatCarriesNoNumberSaysSoWithNullRatherThanZero() {
        val daily = tokensOf(Cadence.Daily)
        assertNull(daily.cadenceCount)
        assertEquals(emptyList(), daily.weekdays)
        assertNull(daily.bound)
        assertNull(daily.boundCount)
        assertNull(daily.boundEpochDays)

        val weekly = tokensOf(Cadence.Weekly(listOf("Wed", "Mon")))
        assertNull(weekly.cadenceCount)
        assertEquals(listOf(1, 3), weekly.weekdays, "ISO numbers, canonical — Swift indexes CLDR off these")

        val counted = tokensOf(Cadence.Monthly(3), RecurrenceBound.AfterCount(10))
        assertEquals(3, counted.cadenceCount)
        assertEquals("AFTER_COUNT", counted.bound)
        assertEquals(10, counted.boundCount)
        assertNull(counted.boundEpochDays, "the other bound's value must not be readable")

        val dated = tokensOf(Cadence.Daily, RecurrenceBound.OnDate(LocalDate(2026, 6, 14)))
        assertEquals("ON_DATE", dated.bound)
        assertEquals(LocalDate(2026, 6, 14).toEpochDays().toInt(), dated.boundEpochDays)
        assertNull(dated.boundCount)
    }

    /** The cursor's tokens are the ones `taskDueRelativeToken` emits, so Swift reuses its `L.relativeDay`. */
    @Test
    fun theCursorTokensReuseTheTaskDetailRelativeDayVocabulary() {
        assertEquals("TOMORROW", tokensOf(Cadence.Daily).cursor)
        assertNull(tokensOf(Cadence.Daily).cursorCount, "a discrete arm carries no plural quantity")

        val away = requireNotNull(
            recurrenceLineTokens(
                item(Cadence.Daily, cursorAt = Instant.parse("2026-06-20T12:00:00Z")),
                zone,
                today,
            ),
        )
        assertEquals("DAYS_AWAY", away.cursor)
        assertEquals(5, away.cursorCount)

        // A cursor pointing BACKWARDS is the normal reading for a missed Habit, not an error state.
        val ago = requireNotNull(
            recurrenceLineTokens(
                item(Cadence.Daily, cursorAt = Instant.parse("2026-06-10T12:00:00Z")),
                zone,
                today,
            ),
        )
        assertEquals("DAYS_AGO", ago.cursor)
        assertEquals(5, ago.cursorCount)

        val exhausted = requireNotNull(recurrenceLineTokens(item(Cadence.Daily, cursorAt = null), zone, today))
        assertEquals("EXHAUSTED", exhausted.cursor)
        assertNull(exhausted.cursorCount)

        val archived = requireNotNull(
            recurrenceLineTokens(item(Cadence.Daily, state = DefinitionState.Archived), zone, today),
        )
        assertNull(archived.cursor, "an archived definition has no next, so Swift renders no clause")
        assertNull(archived.cursorCount)
    }
}
