package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A **relative-day** reading (ADR-0044) — how far a target day is from today, as a typed code the
 * View maps to a localized string (Compose via `Res.string`/`pluralStringResource`, Swift via `L`).
 *
 * Pure and iOS-safe (kotlinx-datetime, no `java.time`), per CLAUDE.md's "non-Compose state carries a
 * typed code, the View maps it" — so it needs no `l10n-parity-overrides.txt` line. Used for the
 * task detail's WHEN row ("N days away") over `Task.completeBy`.
 */
sealed interface RelativeDay {
    data object Today : RelativeDay
    data object Tomorrow : RelativeDay
    data object Yesterday : RelativeDay

    /** Two or more days in the future ([days] >= 2). */
    data class DaysAway(val days: Int) : RelativeDay

    /** Two or more days in the past ([days] >= 2). */
    data class DaysAgo(val days: Int) : RelativeDay
}

/** Today at the system default zone — the default reference for the [relativeDay] readings below. */
internal fun todaySystemDefault(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/**
 * The relative-day reading for [target] against [today]: delta = target − today in whole days.
 * 0 → Today, 1 → Tomorrow, -1 → Yesterday, >= 2 → [RelativeDay.DaysAway], <= -2 → [RelativeDay.DaysAgo].
 */
fun relativeDay(target: LocalDate, today: LocalDate = todaySystemDefault()): RelativeDay {
    val delta = target.toEpochDays() - today.toEpochDays()
    return when {
        delta == 0L -> RelativeDay.Today
        delta == 1L -> RelativeDay.Tomorrow
        delta == -1L -> RelativeDay.Yesterday
        delta >= 2L -> RelativeDay.DaysAway(delta.toInt())
        else -> RelativeDay.DaysAgo((-delta).toInt())
    }
}

/**
 * Convenience over a deadline [instant] (e.g. `Task.completeBy`): resolves it to a day in [zone] and
 * reads it against [today]. Same delta rules as the [LocalDate] overload.
 */
fun relativeDay(
    instant: Instant,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = todaySystemDefault(),
): RelativeDay = relativeDay(instant.toLocalDateTime(zone).date, today)
