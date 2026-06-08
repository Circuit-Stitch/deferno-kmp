package com.circuitstitch.deferno.feature.calendar.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.feature.calendar.CalendarComponent
import kotlinx.datetime.LocalDate

/**
 * The **Calendar** Destination View, desktop edition (#74, ADR-0017) — the desktop counterpart of the
 * Android screen (ADR-0007: not the phone layout stretched). A thin renderer of the shared
 * [CalendarComponent] (ADR-0003: holds no logic): it observes the visible month's markers + the
 * selected day's agenda and forwards day selection, month paging, and the occurrence acts.
 *
 * Desktop divergence: on a **wide** content area it lays the month grid **beside** the day agenda (a
 * two-pane reading layout, [calendarUsesTwoPane]) so paging the month and acting on the day are visible
 * at once — and the tap-a-grid-day reschedule flow keeps both panes on screen. On a **narrow** window it
 * collapses to the shared single-pane stack (the same [CalendarContent] the phone renders). The shell's
 * New (Ctrl+N / View menu / "+ New") opens **New** pre-dated to the selected day while the Calendar is
 * foreground (the desktop counterpart of the Android FAB, routed through `onNewForSelectedDay`, #74).
 */
@Composable
fun CalendarDesktopScreen(component: CalendarComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    CalendarDesktopContent(
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

/**
 * At/above this **content** width the desktop Calendar uses the two-pane (grid │ agenda) layout; below
 * it, the shared single-pane stack. Sized so each pane keeps a comfortable width — a full month grid and
 * a readable agenda beside it. Pure + internal so the breakpoint is unit-tested directly (ADR-0008 G1,
 * width-driven not device-type; cf. `desktopNavKindFor`).
 */
internal const val CALENDAR_TWO_PANE_MIN_WIDTH_DP = 760

internal fun calendarUsesTwoPane(widthDp: Int): Boolean = widthDp >= CALENDAR_TWO_PANE_MIN_WIDTH_DP

/**
 * Stateless desktop body — easy to render in a test with fixed inputs. The month grid and the day agenda
 * are arranged **two-pane** (side by side) on a wide content area and **stacked** otherwise; both
 * arrangements compose the same atoms and share one **hoisted** reschedule-arming state, so arming a
 * reschedule and then resizing the window across the breakpoint never drops it (ADR-0008 G5). A
 * "Reschedule" tap in the agenda arms a pick-a-day mode that the next grid day tap completes.
 */
@Composable
internal fun CalendarDesktopContent(
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
    // Hoisted above the responsive branch so an armed reschedule survives a resize across the two-pane
    // breakpoint (ADR-0008 G5) — both arrangements read and write this one state.
    var reschedulingItem by remember { mutableStateOf<CalendarItem?>(null) }

    // A grid day tap either completes an armed reschedule or selects that day for the agenda.
    val onDayTap: (LocalDate) -> Unit = { date ->
        val rescheduling = reschedulingItem
        if (rescheduling != null) {
            onReschedule(rescheduling.id, date)
            reschedulingItem = null
        } else {
            onDaySelected(date)
        }
    }

    // The grid side (header + weekday row + month grid + the active reschedule banner) and the day agenda
    // are each composed once and arranged two ways below — side by side, or stacked.
    @Composable
    fun GridSide(paneModifier: Modifier) {
        Column(paneModifier) {
            MonthHeader(visibleMonth = visibleMonth, onPrevious = onPreviousMonth, onNext = onNextMonth)
            WeekdayHeader()
            MonthGrid(visibleMonth = visibleMonth, selectedDay = selectedDay, markers = markers, onDayTap = onDayTap)
            reschedulingItem?.let { item ->
                RescheduleBanner(item = item, onCancel = { reschedulingItem = null })
            }
        }
    }

    @Composable
    fun Agenda(paneModifier: Modifier) {
        DayAgenda(
            date = selectedDay,
            items = agenda,
            onMark = onMark,
            onClear = onClear,
            onStartReschedule = { reschedulingItem = it },
            modifier = paneModifier,
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (calendarUsesTwoPane(maxWidth.value.toInt())) {
            Row(Modifier.fillMaxSize()) {
                GridSide(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                Agenda(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                GridSide(Modifier.fillMaxWidth())
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Agenda(Modifier.fillMaxSize())
            }
        }
    }
}
