package com.circuitstitch.deferno.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.format.currentToday
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_due_today
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import org.jetbrains.compose.resources.stringResource

/**
 * The **day-group header** for a reverse-chron, day-bucketed feed — the Task-detail Trail and the Activity
 * destination (#260) share this one implementation. It renders a [DottedLabelDivider] carrying the group's
 * device-local ISO day ([dayIso]) as the start label, with the localized, uppercased "TODAY" punched into
 * the centre when [dayIso] is the device-local today (read from [currentToday], so a test can pin it via
 * `LocalToday`, and production "today" advances across midnight).
 *
 * Callers key their groups by the same device-zone ISO day (`Instant.localDayIso()`) so a row never lands
 * under the wrong header at a day boundary. The canonical row padding is baked in; pass [surface] as the
 * colour of whatever this divider sits on so the dashes break cleanly around the labels.
 */
@Composable
fun DayGroupHeader(
    dayIso: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.defernoColors.inkMuted,
    surface: Color = MaterialTheme.colorScheme.background,
) {
    DottedLabelDivider(
        modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
        startLabel = dayIso,
        centerLabel = if (dayIso == currentToday.toString()) {
            stringResource(Res.string.tasks_detail_due_today).uppercase()
        } else {
            null
        },
        color = color,
        surface = surface,
    )
}
