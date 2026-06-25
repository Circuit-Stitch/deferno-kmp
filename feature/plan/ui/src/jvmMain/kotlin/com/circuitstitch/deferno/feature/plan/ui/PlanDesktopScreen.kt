package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.feature.plan.PlanComponent

/**
 * The daily Plan screen, desktop edition — the app's calm home (design-principles.md: "open into
 * today's Plan, not the whole backlog"). Since the "See the trees" redesign the whole dashboard (Today
 * header + suggestion banner + check-dot day list + the local What's next? / Focus sub-screens) is the
 * shared [PlanScreen] in commonMain (ADR-0004 #27), so desktop renders it identically to Android.
 *
 * Desktop divergence (ADR-0007: not the phone layout stretched): the dashboard is held to a comfortable
 * **reading width** and centred rather than spanning a wide window edge-to-edge.
 */
@Composable
fun PlanDesktopScreen(component: PlanComponent, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        PlanScreen(component, Modifier.widthIn(max = PlanReadingWidth).fillMaxSize())
    }
}

/** Comfortable reading column width for the Plan on a wide desktop window. */
private val PlanReadingWidth = 760.dp
