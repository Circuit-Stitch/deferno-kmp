package com.circuitstitch.deferno.core.designsystem.format

import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

class LocalizedDateFormatsTest {

    private val spanish = Locale.forLanguageTag("es")

    @Test
    fun formatInstant_rendersInZoneAndLocale() {
        val instant = Instant.parse("2026-06-21T09:45:00Z")
        assertEquals("Jun 21 · 09:45", formatInstant(instant, "MMM d · HH:mm", ZoneId.of("UTC"), Locale.ENGLISH))
        // Berlin is UTC+2 in June — the zone, not just the words, must localize.
        assertEquals("21. Juni · 11:45", formatInstant(instant, "d. MMM · HH:mm", ZoneId.of("Europe/Berlin"), Locale.GERMAN))
    }

    @Test
    fun formatDate_localizesNamesAndQuotedLiterals() {
        val day = LocalDate(2026, 6, 1) // a Monday
        assertEquals("Monday, June 1", formatDate(day, "EEEE, MMMM d", Locale.ENGLISH))
        // Quoted pattern literals ('de') must pass through as text, with CLDR names around them.
        assertEquals("lunes, 1 de junio", formatDate(day, "EEEE, d 'de' MMMM", spanish))
        assertEquals("junio de 2026", formatDate(day, "LLLL 'de' yyyy", spanish))
    }

    @Test
    fun formatTime_followsTheLocaleClockPattern() {
        assertEquals("2:30 PM", formatTime(LocalTime(14, 30), "h:mm a", Locale.ENGLISH))
        assertEquals("14:30", formatTime(LocalTime(14, 30), "HH:mm", Locale.GERMAN))
    }

    @Test
    fun shortWeekdayLabels_isoOrderMondayFirst() {
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), shortWeekdayLabels(Locale.ENGLISH))
    }
}
