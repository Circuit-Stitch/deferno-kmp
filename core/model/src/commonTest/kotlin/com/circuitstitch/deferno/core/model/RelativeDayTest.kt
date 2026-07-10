package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Contract for the **relative-day** reading (ADR-0044 §1b): a pure, iOS-safe typed code (kotlinx-datetime,
 * no java.time) the View maps to a localized string. delta = target − today in whole days: 0 → Today,
 * 1 → Tomorrow, −1 → Yesterday, ≥2 → DaysAway(n), ≤−2 → DaysAgo(n).
 */
class RelativeDayTest {

    private val today = LocalDate(2026, 6, 15)

    @Test
    fun sameDay_isToday() {
        assertEquals(RelativeDay.Today, relativeDay(LocalDate(2026, 6, 15), today))
    }

    @Test
    fun oneDayAhead_isTomorrow() {
        assertEquals(RelativeDay.Tomorrow, relativeDay(LocalDate(2026, 6, 16), today))
    }

    @Test
    fun oneDayBehind_isYesterday() {
        assertEquals(RelativeDay.Yesterday, relativeDay(LocalDate(2026, 6, 14), today))
    }

    @Test
    fun twoDaysAhead_isDaysAway2() {
        assertEquals(RelativeDay.DaysAway(2), relativeDay(LocalDate(2026, 6, 17), today))
    }

    @Test
    fun aWeekAhead_isDaysAway7() {
        assertEquals(RelativeDay.DaysAway(7), relativeDay(LocalDate(2026, 6, 22), today))
    }

    @Test
    fun twoDaysBehind_isDaysAgo2() {
        assertEquals(RelativeDay.DaysAgo(2), relativeDay(LocalDate(2026, 6, 13), today))
    }

    @Test
    fun aWeekBehind_isDaysAgo7() {
        assertEquals(RelativeDay.DaysAgo(7), relativeDay(LocalDate(2026, 6, 8), today))
    }

    @Test
    fun instantOverload_resolvesTheDayInTheGivenZone() {
        // 2026-06-15T23:00Z is the 16th in UTC+2 but still the 15th in UTC — the zone chooses the day, so
        // the same instant reads as a different relative day depending on the zone the reading projects it to.
        val instant = Instant.parse("2026-06-15T23:00:00Z")
        assertEquals(RelativeDay.Today, relativeDay(instant, TimeZone.UTC, today))
        assertEquals(RelativeDay.Tomorrow, relativeDay(instant, TimeZone.of("UTC+2"), today))
    }
}
