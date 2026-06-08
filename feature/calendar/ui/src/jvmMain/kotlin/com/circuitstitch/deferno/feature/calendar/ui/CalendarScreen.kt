package com.circuitstitch.deferno.feature.calendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.feature.calendar.CalendarComponent

/**
 * The desktop-native Calendar screen (#74) — the desktop counterpart of the Android screen, reusing
 * the same commonMain atoms (the month grid + day agenda). A thin renderer of the shared
 * [CalendarComponent]; the full desktop calendar polish rides the desktop-shell parity work (ADR-0017).
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
