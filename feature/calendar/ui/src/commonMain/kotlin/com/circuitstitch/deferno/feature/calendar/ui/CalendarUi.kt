package com.circuitstitch.deferno.feature.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

// Stateless building blocks for the Calendar View (#74): a month grid + a day agenda over Occurrences.
// Kept in commonMain (Android + desktop) so both platform screens share them. Gentle by default — no
// "overdue"/"late"/"missed" anywhere; a past unfinished firing simply reads "Scheduled"
// (design-principle #4). The Android/desktop screens that host them live in androidMain/jvmMain.

/** Minimum height for a tappable control — design-principles.md "≥44–48dp" touch targets. */
internal val MinTouchTarget = 48.dp

/** A [LocalTime] as a friendly 12-hour clock, e.g. `14:30` → "2:30 PM" (#348 agenda display). */
internal fun LocalTime.toClock(): String {
    val h12 = (hour % 12).let { if (it == 0) 12 else it }
    val suffix = if (hour < 12) "AM" else "PM"
    return "$h12:${minute.toString().padStart(2, '0')} $suffix"
}

private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * The stateless Calendar body — a [MonthHeader] + month grid over the visible month, then the selected
 * day's day agenda. Rendered directly by the screens and the UI tests with fixed inputs.
 *
 * Rescheduling reuses the grid: tapping "Reschedule" on an Event row arms a pick-a-day mode (local
 * state), and the next day-cell tap reschedules to that day — no platform date picker, identical on
 * every platform and in tests.
 */
@Composable
fun CalendarContent(
    visibleMonth: LocalDate,
    selectedDay: LocalDate,
    markers: Map<LocalDate, Int>,
    agenda: List<CalendarItem>,
    onDaySelected: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onMark: (itemId: String, action: OccurrenceAction) -> Unit,
    onClear: (itemId: String) -> Unit,
    onReschedule: (itemId: String, newDate: LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reschedulingItem by remember { mutableStateOf<CalendarItem?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        MonthHeader(visibleMonth = visibleMonth, onPrevious = onPreviousMonth, onNext = onNextMonth)
        WeekdayHeader()
        MonthGrid(
            visibleMonth = visibleMonth,
            selectedDay = selectedDay,
            markers = markers,
            onDayTap = { date ->
                val rescheduling = reschedulingItem
                if (rescheduling != null) {
                    onReschedule(rescheduling.id, date)
                    reschedulingItem = null
                } else {
                    onDaySelected(date)
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        reschedulingItem?.let { item ->
            RescheduleBanner(item = item, onCancel = { reschedulingItem = null })
        }
        DayAgenda(
            date = selectedDay,
            items = agenda,
            onMark = onMark,
            onClear = onClear,
            onStartReschedule = { reschedulingItem = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The month label (e.g. "June 2026") flanked by previous / next paging arrows. */
@Composable
internal fun MonthHeader(visibleMonth: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier.size(MinTouchTarget).semantics { contentDescription = "Previous month" },
        ) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
        Text(
            text = monthLabel(visibleMonth),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(
            onClick = onNext,
            modifier = Modifier.size(MinTouchTarget).semantics { contentDescription = "Next month" },
        ) { Text("›", style = MaterialTheme.typography.headlineSmall) }
    }
}

/** The Monday-start weekday column headers. */
@Composable
internal fun WeekdayHeader(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        WEEKDAY_LABELS.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.defernoColors.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clearAndSetSemantics {},
            )
        }
    }
}

/** A 6-week month grid of day cells; padding days (other months) are dimmed. */
@Composable
internal fun MonthGrid(
    visibleMonth: LocalDate,
    selectedDay: LocalDate,
    markers: Map<LocalDate, Int>,
    onDayTap: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = calendarGridDays(visibleMonth)
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        inMonth = date.month == visibleMonth.month && date.year == visibleMonth.year,
                        isSelected = date == selectedDay,
                        markerCount = markers[date] ?: 0,
                        onClick = { onDayTap(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One day cell: the day number, a marker dot when there are entries, selected/other-month styling. */
@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isSelected: Boolean,
    markerCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val numberColor = when {
        isSelected -> scheme.onPrimary
        inMonth -> scheme.onSurface
        else -> MaterialTheme.defernoColors.inkMuted
    }
    val description = buildString {
        append(monthName(date.month)).append(' ').append(date.day)
        append(if (markerCount > 0) ", $markerCount ${if (markerCount == 1) "item" else "items"}" else ", no items")
        if (isSelected) append(", selected")
    }
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .padding(2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (isSelected) scheme.primary else scheme.surface)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = numberColor,
            )
            // The marker: a small dot for "this day has entries" (count not shown, to keep cells calm).
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            markerCount <= 0 -> Color.Transparent
                            isSelected -> scheme.onPrimary
                            else -> MaterialTheme.defernoColors.amberDeep
                        },
                    ),
            )
        }
    }
}

/** A banner shown while a firing is being rescheduled: pick a new day in the grid above (or beside, on desktop). */
@Composable
internal fun RescheduleBanner(item: CalendarItem, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Pick a new day for “${item.title}”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** The selected day's agenda: its Occurrences + dated items, or a gentle empty state. */
@Composable
internal fun DayAgenda(
    date: LocalDate,
    items: List<CalendarItem>,
    onMark: (itemId: String, action: OccurrenceAction) -> Unit,
    onClear: (itemId: String) -> Unit,
    onStartReschedule: (CalendarItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = agendaHeading(date),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
        )
        if (items.isEmpty()) {
            EmptyAgenda()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    AgendaRow(
                        item = item,
                        onMark = onMark,
                        onClear = onClear,
                        onStartReschedule = onStartReschedule,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** Gentle, non-judgmental empty agenda (design-principles.md: lapses are normal; no pressure). */
@Composable
private fun EmptyAgenda(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing on this day", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "A clear day. Add something when you're ready — no pressure.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** One agenda entry: title + neutral status, and the kind-aware action set for an actionable firing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgendaRow(
    item: CalendarItem,
    onMark: (itemId: String, action: OccurrenceAction) -> Unit,
    onClear: (itemId: String) -> Unit,
    onStartReschedule: (CalendarItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                // The firing's start clock (#348), shown only for timed rows (not all-day). The start
                // instant carries the real time-of-day; projected for display.
                // ponytail: device zone is fine for a single-user v1 — thread the account TimeZone when
                // device-zone ≠ account-zone matters.
                if (!item.allDay) {
                    Text(
                        text = item.start.toLocalDateTime(TimeZone.currentSystemDefault()).time.toClock(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.defernoColors.inkMuted,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            AgendaStatusChip(item.status)
        }
        if (item.isActionableOccurrence) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Habit firings are binary (Done / Clear); chore/event firings carry start / done / skip.
                if (item.kind != ItemKind.Habit) {
                    ActionChip("Start") { onMark(item.id, OccurrenceAction.Start) }
                }
                ActionChip("Done") { onMark(item.id, OccurrenceAction.Complete) }
                if (item.kind != ItemKind.Habit) {
                    ActionChip("Skip") { onMark(item.id, OccurrenceAction.Skip) }
                }
                ActionChip("Clear") { onClear(item.id) }
                // Reschedule is Events-only in v1 (habit/chore reschedule is server-unimplemented; moving a
                // row that would snap back reads as shaming-by-failure, design-principle #4).
                if (item.kind == ItemKind.Event) {
                    ActionChip("Reschedule") { onStartReschedule(item) }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = 36.dp),
    )
}

/** A neutral status label — colour reinforces, never the sole signal (WCAG); no shaming vocabulary. */
@Composable
private fun AgendaStatusChip(status: WorkingState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val brand = MaterialTheme.defernoColors
    val (label, container, content) = when (status) {
        // A scheduled-but-unfinished firing — including a past one — reads plainly as "Scheduled"
        // (design-principle #4: no "overdue"/"missed"/"late").
        WorkingState.Open -> Triple("Scheduled", scheme.surfaceVariant, scheme.onSurfaceVariant)
        WorkingState.InProgress -> Triple("In progress", scheme.primaryContainer, scheme.onPrimaryContainer)
        WorkingState.InReview -> Triple("In review", scheme.secondaryContainer, scheme.onSecondaryContainer)
        WorkingState.Done -> Triple("Done", brand.successContainer, brand.onSuccessContainer)
        WorkingState.Dropped -> Triple("Skipped", scheme.surfaceVariant, brand.inkMuted)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = "Status: $label" },
    )
}

// --- pure helpers (date → display) ---

/** "June 2026" — the visible-month label. */
internal fun monthLabel(date: LocalDate): String = "${monthName(date.month)} ${date.year}"

/** "Monday, June 8" — the agenda heading for a day. */
internal fun agendaHeading(date: LocalDate): String =
    "${weekdayName(date)}, ${monthName(date.month)} ${date.day}"

/** The 42 day cells (6 weeks) for [visibleMonth]'s grid, Monday-start — matches the repository window. */
internal fun calendarGridDays(visibleMonth: LocalDate): List<LocalDate> {
    val first = LocalDate(visibleMonth.year, visibleMonth.month, 1)
    val lead = first.dayOfWeek.isoDayNumber - 1
    val start = first.minus(lead, DateTimeUnit.DAY)
    return (0 until 42).map { start.plus(it, DateTimeUnit.DAY) }
}

private fun monthName(month: Month): String = when (month) {
    Month.JANUARY -> "January"
    Month.FEBRUARY -> "February"
    Month.MARCH -> "March"
    Month.APRIL -> "April"
    Month.MAY -> "May"
    Month.JUNE -> "June"
    Month.JULY -> "July"
    Month.AUGUST -> "August"
    Month.SEPTEMBER -> "September"
    Month.OCTOBER -> "October"
    Month.NOVEMBER -> "November"
    Month.DECEMBER -> "December"
}

private fun weekdayName(date: LocalDate): String = when (date.dayOfWeek.isoDayNumber) {
    1 -> "Monday"
    2 -> "Tuesday"
    3 -> "Wednesday"
    4 -> "Thursday"
    5 -> "Friday"
    6 -> "Saturday"
    else -> "Sunday"
}
