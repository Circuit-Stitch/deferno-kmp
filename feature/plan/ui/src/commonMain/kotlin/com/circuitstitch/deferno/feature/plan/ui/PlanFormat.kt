package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.runtime.Composable
import com.circuitstitch.deferno.core.designsystem.format.formatDate
import com.circuitstitch.deferno.core.designsystem.format.formatTime
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_time_pattern
import com.circuitstitch.deferno.core.designsystem.resources.plan_deadline_anytime
import com.circuitstitch.deferno.core.designsystem.resources.plan_header_date_pattern
import com.circuitstitch.deferno.core.model.Task
import java.util.Locale
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

// Locale-aware formatting helpers for the Plan View: the words come from the JDK's CLDR data via
// LocalizedDateFormats; the field order comes from per-locale pattern resources. The Today header
// keeps its calm ALL-CAPS mono style by uppercasing the localized rendering.

/** "MON JUN 16" — the calm mono date in the Today header, in the device's language. */
@Composable
internal fun formatHeaderDate(date: LocalDate): String =
    formatDate(date, stringResource(Res.string.plan_header_date_pattern)).uppercase(Locale.getDefault())

/** "MON JUN 16" for a deadline day (used in the "why" line of a choice). */
@Composable
internal fun formatDeadlineDate(instant: Instant, tz: TimeZone): String =
    formatHeaderDate(instant.toLocalDateTime(tz).date)

/** The meta subline for a Task row: its deadline clock time (e.g. "8:00 PM" / "20:00"), else "anytime". */
@Composable
internal fun Task.deadlineLabel(): String =
    deadlineTimeOfDay?.let { formatTime(it, stringResource(Res.string.common_time_pattern)) }
        ?: stringResource(Res.string.plan_deadline_anytime)
