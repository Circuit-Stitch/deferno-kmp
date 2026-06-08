package com.circuitstitch.deferno.feature.calendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.feature.calendar.CalendarComponent

/**
 * The Calendar Destination (#74) — a single-pane month grid + day agenda over Occurrences. A thin
 * renderer of [CalendarComponent]: observes the visible month's markers + the selected day's agenda
 * and forwards day selection, month paging, and the occurrence acts, holding no logic of its own. The
 * shell's FAB opens **New** pre-dated to the selected day (via the component's `onNewForSelectedDay`).
 * Its reusable atoms live in commonMain (CalendarUi.kt) for the desktop View to share.
 */
@Composable
fun CalendarScreen(component: CalendarComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    CalendarContent(
        visibleMonth = state.visibleMonth,
        selectedDay = state.selectedDay,
        markers = state.markers,
        agenda = state.agenda,
        onDaySelected = component::onDaySelected,
        onPreviousMonth = component::onShowPreviousMonth,
        onNextMonth = component::onShowNextMonth,
        onMark = component::onMark,
        onClear = component::onClear,
        onReschedule = component::onReschedule,
        modifier = modifier,
    )
}
