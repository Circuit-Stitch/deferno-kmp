package com.circuitstitch.deferno.core.data.recurring

import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
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
 */
class RecurrenceColumnCodecTest {

    @Test
    fun aNullTypeColumnIsADefinitionWithNoRuleNotAnUnknownRule() {
        // The pre-existing contract, preserved: null `recurrence_type` -> null Recurrence. It must stay
        // distinct from "a rule whose cadence we could not read", which decodes to Unknown.
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

        assertEquals(
            Recurrence(RecurrenceFrequency.Weekly, days = listOf("Mon", "Wed")),
            decodeRecurrence(legacy),
        )
    }

    @Test
    fun anUnrecognisedFrequencyTokenDegradesToUnknownRatherThanThrowing() {
        // A row written by a NEWER client, whose cadence this build does not have an enum entry for.
        val decoded = decodeRecurrence(RecurrenceColumns(type = "Fortnightly"))

        assertEquals(RecurrenceFrequency.Unknown, decoded?.frequency)
    }

    @Test
    fun anUnrecognisedOrHalfPopulatedAnchorDegradesToNull() {
        // A monthly rule that cannot say WHICH day is still a usable monthly rule. Inventing a day would
        // be worse than admitting we don't have one, and throwing would take the whole list down.
        fun anchorOf(columns: RecurrenceColumns) = decodeRecurrence(columns.copy(type = "Monthly"))?.monthlyAnchor

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
        val columns = Recurrence(RecurrenceFrequency.Daily).encodeColumns()

        assertNull(columns.endType)
        assertNull(columns.endDate)
        assertNull(columns.endCount)
        assertNull(columns.anchorType)
        assertEquals("Daily", columns.type)
        assertEquals("", columns.days)
    }

    @Test
    fun theCadenceParametersEncodeIntoTheirOwnColumnsAndBackAgain() {
        // The anchor's day is kept in `recurrence_anchor_day`, NOT shared with the yearly
        // `recurrence_day`. Sharing one column would fit (monthly and yearly are mutually exclusive) but
        // would make the decode ambiguous — a monthly row would come back with a stray day-of-month and
        // the round-trip would stop being an identity. These two assertions are what pin that apart.
        val monthly = Recurrence(
            RecurrenceFrequency.Monthly,
            interval = 2,
            monthlyAnchor = MonthlyAnchor.DayOfMonth(15),
        ).encodeColumns()
        assertEquals(15L, monthly.anchorDay)
        assertNull(monthly.day, "a monthly rule writes no yearly day-of-month")

        val yearly = Recurrence(RecurrenceFrequency.Yearly, interval = 1, month = 6, day = 14).encodeColumns()
        assertEquals(14L, yearly.day)
        assertNull(yearly.anchorDay, "a yearly rule writes no monthly anchor day")

        assertEquals(
            Recurrence(RecurrenceFrequency.Monthly, interval = 2, monthlyAnchor = MonthlyAnchor.DayOfMonth(15)),
            decodeRecurrence(monthly),
        )
        assertEquals(
            Recurrence(RecurrenceFrequency.Yearly, interval = 1, month = 6, day = 14),
            decodeRecurrence(yearly),
        )
    }

    @Test
    fun theRawTokenOfAnUnmodellableCadenceIsPersistedAndRead() {
        // The cache half of "preserve what we can't render". Without this column the row would come back
        // as the bare frequency name "Unknown" and the original cadence would be gone for good.
        val future = Recurrence(RecurrenceFrequency.Unknown, rawType = "fortnightly", interval = 2)
        val columns = future.encodeColumns()

        assertEquals("Unknown", columns.type)
        assertEquals("fortnightly", columns.rawType)
        assertEquals(future, decodeRecurrence(columns))
    }
}
