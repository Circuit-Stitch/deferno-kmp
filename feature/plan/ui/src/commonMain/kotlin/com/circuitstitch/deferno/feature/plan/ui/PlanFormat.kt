package com.circuitstitch.deferno.feature.plan.ui

import com.circuitstitch.deferno.core.model.Task
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

// Small, locale-light formatting helpers for the Plan View — commonMain (no java.time.format here),
// so they're hand-rolled. ponytail: English-only, fixed forms; good enough for the "See the trees"
// header/meta lines. A real i18n pass would route through platform formatters.

private fun DayOfWeek.short(): String = when (this) {
    DayOfWeek.MONDAY -> "MON"
    DayOfWeek.TUESDAY -> "TUE"
    DayOfWeek.WEDNESDAY -> "WED"
    DayOfWeek.THURSDAY -> "THU"
    DayOfWeek.FRIDAY -> "FRI"
    DayOfWeek.SATURDAY -> "SAT"
    DayOfWeek.SUNDAY -> "SUN"
}

private fun Month.short(): String = when (this) {
    Month.JANUARY -> "JAN"
    Month.FEBRUARY -> "FEB"
    Month.MARCH -> "MAR"
    Month.APRIL -> "APR"
    Month.MAY -> "MAY"
    Month.JUNE -> "JUN"
    Month.JULY -> "JUL"
    Month.AUGUST -> "AUG"
    Month.SEPTEMBER -> "SEP"
    Month.OCTOBER -> "OCT"
    Month.NOVEMBER -> "NOV"
    Month.DECEMBER -> "DEC"
}

/** "MON JUN 16" — the calm mono date in the Today header. */
internal fun formatHeaderDate(date: LocalDate): String =
    "${date.dayOfWeek.short()} ${date.month.short()} ${date.day}"

/** "MON JUN 16" for a deadline day (used in the "why" line of a choice). */
internal fun formatDeadlineDate(instant: Instant, tz: TimeZone): String =
    formatHeaderDate(instant.toLocalDateTime(tz).date)

/** "8:00 PM" — 12-hour clock time for a deadline time-of-day. */
internal fun formatClockTime(time: LocalTime): String {
    val h24 = time.hour
    val h12 = when {
        h24 == 0 -> 12
        h24 > 12 -> h24 - 12
        else -> h24
    }
    val minute = time.minute.toString().padStart(2, '0')
    val suffix = if (h24 < 12) "AM" else "PM"
    return "$h12:$minute $suffix"
}

/** The meta subline for a Task row: its deadline clock time, else "anytime". */
internal fun Task.deadlineLabel(): String =
    deadlineTimeOfDay?.let(::formatClockTime) ?: "anytime"

/** Today's date in the system zone — the default when the host doesn't thread one in. */
internal fun systemToday(): LocalDate =
    Clock.System.todayIn(TimeZone.currentSystemDefault())
