package com.circuitstitch.deferno.core.data.recurring

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The row<->domain codec for the widened recurrence columns (#382) — the shape all three recurring
 * tables persist, factored into one place because the generated entity types share no supertype.
 *
 * `RecurringLocalStoreTest` proves the *happy* round-trip against genuine SQLite. This covers what a
 * round-trip structurally cannot reach: the **degradation** paths, whose inputs can only come from a row
 * this build did not write — a cache written by a newer client, a partially-applied write, or a legacy
 * row. The house rule for every one of them is *degrade, never throw*: a read that throws inside the
 * observe Flow takes the screen down, which is the same class of failure as #381's decode stall.
 *
 * It is also the guard on the stored `recurrence_type` tokens. They are a **persisted format** — the
 * names of the enum the domain used before it became a sealed [Cadence] — so the string literals spelled
 * out below are load-bearing: change one and every cached rule of that cadence silently stops decoding.
 */
class RecurrenceColumnCodecTest {

    @Test
    fun aNullTypeColumnIsADefinitionWithNoRuleNotAnUnreadableRule() {
        // The pre-existing contract, preserved: null `recurrence_type` -> null Recurrence. It must stay
        // distinct from "a rule whose cadence we could not read", which decodes to Unmodelled.
        assertNull(decodeRecurrence(RecurrenceColumns()))
        assertNull(decodeRecurrence(RecurrenceColumns(type = null, interval = 3, endCount = 10)))
        assertEquals(RecurrenceColumns(), (null as Recurrence?).encodeColumns())
    }

    @Test
    fun aPreMigrationRowDecodesToExactlyTheRuleItAlreadyHeld() {
        // Why 18->19 needs NO back-fill: every added column is NULL on an existing row, and NULL is the
        // correct reading for all of them — no parameters, and (for end_type) the Never bound. So a row
        // written by the previous build reads back as the same rule it always was.
        val legacy = RecurrenceColumns(type = "Weekly", days = "Mon\nWed")

        assertEquals(Recurrence(Cadence.Weekly(listOf("Mon", "Wed"))), decodeRecurrence(legacy))
    }

    @Test
    fun theStoredCadenceTokensAreTheOnesEarlierBuildsWrote() {
        // A cache written by the CURRENT release must keep decoding after the domain became sealed, so
        // the tokens stayed the old enum names — including "Unknown" for the unmodelled arm. These
        // literals are the contract; the constants in the codec are private on purpose.
        val encoded = listOf(
            Cadence.Daily,
            Cadence.EveryNDays(30),
            Cadence.Weekly(listOf("Mon")),
            Cadence.Monthly(interval = 2),
            Cadence.Yearly(interval = 1, month = 6, day = 14),
            Cadence.Custom("FREQ=DAILY"),
            Cadence.Unmodelled("fortnightly"),
        ).map { Recurrence(it).encodeColumns().type }

        assertEquals(
            listOf("Daily", "EveryNDays", "Weekly", "Monthly", "Yearly", "Custom", "Unknown"),
            encoded,
        )
        // …and each of those tokens still decodes back to its own cadence.
        assertEquals(Cadence.Daily, decodeRecurrence(RecurrenceColumns(type = "Daily"))?.cadence)
        assertEquals(
            Cadence.EveryNDays(30),
            decodeRecurrence(RecurrenceColumns(type = "EveryNDays", interval = 30))?.cadence,
        )
        assertEquals(
            Cadence.Custom("FREQ=DAILY"),
            decodeRecurrence(RecurrenceColumns(type = "Custom", rrule = "FREQ=DAILY"))?.cadence,
        )
    }

    @Test
    fun anUnrecognisedCadenceTokenDegradesToUnmodelledUnderWhicheverNameTheRowStillHas() {
        // A row written by a NEWER client, whose cadence this build has no variant for. The token itself
        // is all there is to keep, so it becomes the name.
        assertEquals(
            Cadence.Unmodelled("Fortnightly"),
            decodeRecurrence(RecurrenceColumns(type = "Fortnightly"))?.cadence,
        )
        // The historical "Unknown" placeholder is not a cadence name — the real one is in `raw_type`.
        assertEquals(
            Cadence.Unmodelled("fortnightly"),
            decodeRecurrence(RecurrenceColumns(type = "Unknown", rawType = "fortnightly"))?.cadence,
        )
        // …and a placeholder row with no preserved token degrades to a BLANK one, never to the literal
        // string "Unknown". Exporting that as a wire `type` is what made such a backup unrestorable.
        assertEquals(
            Cadence.Unmodelled(""),
            decodeRecurrence(RecurrenceColumns(type = "Unknown"))?.cadence,
        )
    }

    @Test
    fun anAbsentCycleColumnDecodesAsEveryOneOfThemRatherThanThrowing() {
        // The sealed cadences require a cycle; the column does not. A row that lost it (a partial write,
        // or a pre-#382 row that never had one) reads as `1` — the wire's own default for `n`/`interval`.
        assertEquals(Cadence.EveryNDays(1), decodeRecurrence(RecurrenceColumns(type = "EveryNDays"))?.cadence)
        assertEquals(Cadence.Monthly(interval = 1), decodeRecurrence(RecurrenceColumns(type = "Monthly"))?.cadence)
        assertEquals(
            Cadence.Yearly(interval = 1, month = 1, day = 1),
            decodeRecurrence(RecurrenceColumns(type = "Yearly"))?.cadence,
        )
        assertEquals(Cadence.Custom(""), decodeRecurrence(RecurrenceColumns(type = "Custom"))?.cadence)
    }

    @Test
    fun anUnrecognisedOrHalfPopulatedAnchorDegradesToNull() {
        // A monthly rule that cannot say WHICH day is still a usable monthly rule. Inventing a day would
        // be worse than admitting we don't have one, and throwing would take the whole list down.
        fun anchorOf(columns: RecurrenceColumns) =
            (decodeRecurrence(columns.copy(type = "Monthly"))?.cadence as? Cadence.Monthly)?.on

        assertEquals(
            MonthlyAnchor.DayOfMonth(15),
            anchorOf(RecurrenceColumns(anchorType = "DayOfMonth", anchorDay = 15)),
        )
        assertEquals(
            MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri"),
            anchorOf(RecurrenceColumns(anchorType = "NthWeekday", anchorNth = -1, anchorWeekday = "Fri")),
        )

        assertNull(anchorOf(RecurrenceColumns()), "no anchor at all")
        assertNull(anchorOf(RecurrenceColumns(anchorType = "LastBusinessDay")), "an unknown anchor token")
        assertNull(anchorOf(RecurrenceColumns(anchorType = "DayOfMonth")), "DayOfMonth with a null day")
        assertNull(anchorOf(RecurrenceColumns(anchorType = "NthWeekday", anchorNth = 2)), "no weekday")
        assertNull(anchorOf(RecurrenceColumns(anchorType = "NthWeekday", anchorWeekday = "Wed")), "no nth")
    }

    @Test
    fun aNullOrUnreadableEndTypeDegradesToTheNeverBound() {
        fun boundOf(columns: RecurrenceColumns) = decodeRecurrence(columns.copy(type = "Daily"))?.bound

        // NULL end_type is the CORRECT reading, not merely a safe one: it mirrors the wire, where an
        // absent `end` key IS the never bound.
        assertEquals(RecurrenceBound.Never, boundOf(RecurrenceColumns()))
        assertEquals(
            RecurrenceBound.OnDate(LocalDate(2027, 1, 31)),
            boundOf(RecurrenceColumns(endType = "OnDate", endDate = "2027-01-31")),
        )
        assertEquals(
            RecurrenceBound.AfterCount(10),
            boundOf(RecurrenceColumns(endType = "AfterCount", endCount = 10)),
        )

        assertEquals(RecurrenceBound.Never, boundOf(RecurrenceColumns(endType = "UntilCancelled")), "unknown")
        assertEquals(RecurrenceBound.Never, boundOf(RecurrenceColumns(endType = "OnDate")), "no date")
        assertEquals(
            RecurrenceBound.Never,
            boundOf(RecurrenceColumns(endType = "OnDate", endDate = "31/01/2027")),
            "an unparseable date must not throw out of the observe Flow",
        )
        assertEquals(RecurrenceBound.Never, boundOf(RecurrenceColumns(endType = "AfterCount")), "no count")
    }

    @Test
    fun theNeverBoundAndAnAbsentAnchorEncodeToNullColumns() {
        // The write side of the same invariant: Never stores NULL rather than the string "Never", so the
        // column means the same thing as the wire's absent `end` key.
        val columns = Recurrence(Cadence.Daily).encodeColumns()

        assertNull(columns.endType)
        assertNull(columns.endDate)
        assertNull(columns.endCount)
        assertNull(columns.anchorType)
        assertEquals("Daily", columns.type)
        assertEquals("", columns.days)
    }

    @Test
    fun eachCadenceWritesOnlyItsOwnColumnsAndReadsThemBack() {
        // The anchor's day is kept in `recurrence_anchor_day`, NOT shared with the yearly
        // `recurrence_day`. Sharing one column would fit (monthly and yearly are mutually exclusive) but
        // would make the decode ambiguous — a monthly row would come back with a stray day-of-month and
        // the round-trip would stop being an identity. These two assertions are what pin that apart.
        val monthly = Recurrence(
            Cadence.Monthly(interval = 2, on = MonthlyAnchor.DayOfMonth(15)),
        ).encodeColumns()
        assertEquals(15L, monthly.anchorDay)
        assertNull(monthly.day, "a monthly rule writes no yearly day-of-month")

        val yearly = Recurrence(Cadence.Yearly(interval = 1, month = 6, day = 14)).encodeColumns()
        assertEquals(14L, yearly.day)
        assertNull(yearly.anchorDay, "a yearly rule writes no monthly anchor day")
        // A cadence that owns no weekday list writes none, so an every-N-days row can never come back
        // pretending to be weekly.
        assertEquals("", Recurrence(Cadence.EveryNDays(30)).encodeColumns().days)

        assertEquals(
            Recurrence(Cadence.Monthly(interval = 2, on = MonthlyAnchor.DayOfMonth(15))),
            decodeRecurrence(monthly),
        )
        assertEquals(Recurrence(Cadence.Yearly(interval = 1, month = 6, day = 14)), decodeRecurrence(yearly))
    }

    @Test
    fun theRawTokenOfAnUnmodellableCadenceIsPersistedAndRead() {
        // The cache half of "preserve what we can't render". Without this column the row would come back
        // under the bare placeholder "Unknown" and the original cadence would be gone for good.
        val future = Recurrence(Cadence.Unmodelled("fortnightly"))
        val columns = future.encodeColumns()

        assertEquals("Unknown", columns.type)
        assertEquals("fortnightly", columns.rawType)
        assertEquals(future, decodeRecurrence(columns))
    }
}
