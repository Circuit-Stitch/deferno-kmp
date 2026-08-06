package com.circuitstitch.deferno.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_status_a11y
import com.circuitstitch.deferno.core.designsystem.resources.common_status_done_late
import com.circuitstitch.deferno.core.designsystem.resources.common_status_done_on_time
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_progress
import com.circuitstitch.deferno.core.designsystem.resources.common_status_missed
import com.circuitstitch.deferno.core.designsystem.resources.common_status_scheduled
import com.circuitstitch.deferno.core.designsystem.resources.common_status_skipped
import com.circuitstitch.deferno.core.designsystem.resources.common_status_unknown
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.OccurrenceState
import org.jetbrains.compose.resources.stringResource

/**
 * How an [OccurrenceState] — *how one dated firing went* — presents itself: its word and its two colours.
 *
 * This lived in `feature/calendar/ui`'s `CalendarUi.kt` while the day agenda was the only surface that
 * rendered a firing's reading. The recurring-definition detail became the second one (#383, its TODAY
 * row), and a feature slice may not depend on another feature slice — so rather than duplicate a
 * vocabulary that must read identically everywhere, it moves down to the design system, exactly as the
 * kind vocabulary did in [KindAtoms.kt] when the Plan became the tree's second all-kinds surface
 * (ADR-0004). The `common_status_*` strings were already shared from this module; only the composables
 * reading them were in the wrong place.
 *
 * The **firing** reading and the item's own [com.circuitstitch.deferno.core.model.WorkingState] are two
 * different axes and only the former lives here. The calendar's non-firing rows (a one-off dated Task, a
 * synced external event) keep their own `WorkingState` mapping beside the surface that needs it — that
 * one is not a cross-slice vocabulary, and pairing them here would invite a caller to reach for whichever
 * compiles.
 */

/** One chip's rendering: the word a reader sees, the pill behind it, and the ink on it. */
data class StatusChipStyle(val label: String, val container: Color, val content: Color)

/**
 * How a firing's reading paints. Exhaustive over all seven [OccurrenceState] members deliberately — a new
 * state must break this build rather than fall through to a generic label, which is exactly what the Apple
 * side *cannot* have (a Kotlin enum bridges to Swift as an Objective-C class, so a Swift match is an
 * `if`-chain with a silent catch-all).
 *
 * Tone is spent only where it earns its keep:
 * - **Missed** is deliberately not an error colour. The word is already exact; painting it red would add
 *   the reproach ADR-0053 decision 7 says the register must not carry. It shares the muted past-tense
 *   tone with **Skipped**: both are days that have closed, and the difference between them is the word —
 *   which is precise — not the colour, which would only be a verdict.
 * - **Done late** shares Done-on-time's success tone. Both record that the work *happened*; the
 *   punctuality split belongs in the label, where it is information.
 * - **Not synced** ([OccurrenceState.Unknown]) gets no container at all. Absent information must read as
 *   an aside rather than as a badge announcing a state, and above all it must never look like the
 *   Scheduled chip: "we have never looked at that day" and "nothing was due yet" are different claims,
 *   and showing the second when we mean the first is the guess ADR-0053 exists to stop.
 */
@Composable
fun occurrenceStateChipStyle(occurrence: OccurrenceState): StatusChipStyle {
    val scheme = MaterialTheme.colorScheme
    val brand = MaterialTheme.defernoColors
    return when (occurrence) {
        OccurrenceState.Scheduled ->
            StatusChipStyle(stringResource(Res.string.common_status_scheduled), scheme.surfaceVariant, scheme.onSurfaceVariant)
        OccurrenceState.InProgress ->
            StatusChipStyle(stringResource(Res.string.common_status_in_progress), scheme.primaryContainer, scheme.onPrimaryContainer)
        OccurrenceState.DoneOnTime ->
            StatusChipStyle(stringResource(Res.string.common_status_done_on_time), brand.successContainer, brand.onSuccessContainer)
        OccurrenceState.DoneLate ->
            StatusChipStyle(stringResource(Res.string.common_status_done_late), brand.successContainer, brand.onSuccessContainer)
        OccurrenceState.Skipped ->
            StatusChipStyle(stringResource(Res.string.common_status_skipped), scheme.surfaceVariant, brand.inkMuted)
        OccurrenceState.Missed ->
            StatusChipStyle(stringResource(Res.string.common_status_missed), scheme.surfaceVariant, brand.inkMuted)
        OccurrenceState.Unknown ->
            StatusChipStyle(stringResource(Res.string.common_status_unknown), Color.Transparent, brand.inkMuted)
    }
}

/**
 * The status pill itself — colour reinforces, never the sole signal (WCAG): the word is always present,
 * and the node announces that word rather than its paint.
 *
 * [semanticLabel] overrides the default `common_status_a11y` ("Status: …") wrapper for a host whose row
 * frames the reading differently — the definition detail's TODAY row says "Today: …" (#383), and letting
 * it wrap this node from outside would leave two `clearAndSetSemantics` fighting over one subtree.
 */
@Composable
fun StatusChip(
    style: StatusChipStyle,
    modifier: Modifier = Modifier,
    semanticLabel: String? = null,
) {
    val description = semanticLabel ?: stringResource(Res.string.common_status_a11y, style.label)
    Text(
        text = style.label,
        style = MaterialTheme.typography.labelMedium,
        color = style.content,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(style.container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = description },
    )
}

/** [StatusChip] over a firing's reading — the pair every caller of this vocabulary actually wants. */
@Composable
fun OccurrenceStateChip(
    occurrence: OccurrenceState,
    modifier: Modifier = Modifier,
    semanticLabel: String? = null,
) {
    StatusChip(style = occurrenceStateChipStyle(occurrence), modifier = modifier, semanticLabel = semanticLabel)
}
