package com.circuitstitch.deferno.core.designsystem.format

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime

/*
 * Locale-aware date/time display formatting over java.time — available from commonMain because the
 * Compose UI modules target only Android + desktop-JVM (ADR-0004), so this source set is JVM-shared.
 *
 * The [pattern] arguments are java.time [DateTimeFormatter] patterns supplied per locale from string
 * resources (e.g. `Res.string.activity_when_pattern`), so a locale controls both the words and the
 * field order ("Jun 21" vs "21. Juni"); month and weekday names come from the JDK's CLDR locale
 * data, never hand-rolled tables. The zone/locale parameters exist for tests — production call
 * sites use the device defaults.
 */

/** [instant] rendered with [pattern] in the device's time zone and language, e.g. "Jun 21 · 09:45". */
fun formatInstant(
    instant: Instant,
    pattern: String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(instant.toJavaInstant())

/** [date] rendered with [pattern] in the device's language, e.g. "June 2026" or "Monday, June 8". */
fun formatDate(date: LocalDate, pattern: String, locale: Locale = Locale.getDefault()): String =
    date.toJavaLocalDate().format(DateTimeFormatter.ofPattern(pattern, locale))

/** [time] rendered with [pattern] in the device's language, e.g. "2:30 PM" or "14:30". */
fun formatTime(time: LocalTime, pattern: String, locale: Locale = Locale.getDefault()): String =
    time.toJavaLocalTime().format(DateTimeFormatter.ofPattern(pattern, locale))

/** Localized short weekday labels in ISO order (Mon…Sun) — the calendar's Monday-start header. */
fun shortWeekdayLabels(locale: Locale = Locale.getDefault()): List<String> =
    DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, locale) }
