package com.circuitstitch.deferno.desktop.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.feature.plan.ui.PlanDesktopScreen
import com.circuitstitch.deferno.feature.tasks.ui.TasksDesktopScreen

/**
 * The **Main shell** View, desktop edition (ADR-0013): it hosts the [MainShellComponent]'s Destination
 * graph in a desktop-native navigation surface. Rather than reuse the Android `NavigationSuiteScaffold`
 * (an Android-only adaptive artifact), it renders the desktop's own nav suite — a persistent
 * [NavigationRail] that **expands into a [PermanentNavigationDrawer]** on a wide window. It renders one
 * nav item per registered [Destination] (never a fixed count) and the foreground Destination's screen
 * as content.
 *
 * The rail ↔ drawer choice is driven purely by the **continuous available width** (ADR-0008 G1 — never
 * a device-type check) via [desktopNavKindFor]; the View is otherwise a pure renderer of the shared
 * stack, so resizing across the breakpoint never drops state (G5).
 */
@Composable
fun MainShell(component: MainShellComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    val active = stack.active.instance
    BoxWithConstraints(modifier.fillMaxSize()) {
        when (desktopNavKindFor(maxWidth.value.toInt())) {
            DesktopNavKind.Drawer ->
                PermanentNavigationDrawer(
                    drawerContent = {
                        PermanentDrawerSheet(Modifier.width(DrawerWidth)) {
                            component.destinations.forEach { destination ->
                                NavigationDrawerItem(
                                    label = { Text(destination.label) },
                                    selected = destination == active.destination,
                                    onClick = { component.selectDestination(destination) },
                                    icon = { Icon(destination.icon, contentDescription = null) },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                        }
                    },
                ) {
                    DestinationContent(active)
                }

            DesktopNavKind.Rail ->
                Row(Modifier.fillMaxSize()) {
                    NavigationRail {
                        component.destinations.forEach { destination ->
                            NavigationRailItem(
                                selected = destination == active.destination,
                                onClick = { component.selectDestination(destination) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                    DestinationContent(active)
                }
        }
    }
}

/** Renders the foreground Destination's desktop screen, filling the content area. */
@Composable
private fun DestinationContent(active: MainShellComponent.DestinationChild) {
    when (active) {
        is MainShellComponent.DestinationChild.Plan ->
            PlanDesktopScreen(active.component, Modifier.fillMaxSize())

        is MainShellComponent.DestinationChild.Tasks ->
            TasksDesktopScreen(active.component, Modifier.fillMaxSize())
    }
}

/** The desktop nav-suite shapes: a compact [Rail] or, on a wide window, an expanded [Drawer]. */
enum class DesktopNavKind { Rail, Drawer }

/** Width of the permanent navigation drawer when expanded. */
private val DrawerWidth = 260.dp

/**
 * At/above this available width the desktop nav suite expands from a rail to a permanent drawer. Past
 * the app's mobile breakpoints (600/840dp) because a desktop window is wide by default and only the
 * roomiest windows benefit from the always-labelled drawer beside the (often two-pane) content.
 */
internal const val DRAWER_MIN_WIDTH_DP = 1240

/**
 * Map the continuous window width (dp) to the desktop nav-suite shape (ADR-0008 G1 / ADR-0007
 * size-class adaptation): a [NavigationRail] until [DRAWER_MIN_WIDTH_DP], then a
 * [PermanentNavigationDrawer]. Reads only the width metric — never device-type checks. Pure +
 * internal so the breakpoint is unit-tested directly.
 */
internal fun desktopNavKindFor(widthDp: Int): DesktopNavKind =
    if (widthDp >= DRAWER_MIN_WIDTH_DP) DesktopNavKind.Drawer else DesktopNavKind.Rail

/** The nav-suite label for a [Destination] — a View concern, kept out of the shared registry. */
internal val Destination.label: String
    get() = when (this) {
        Destination.Plan -> "Plan"
        Destination.Tasks -> "Tasks"
    }

/** The nav-suite icon for a [Destination] — a View concern, kept out of the shared registry. */
private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Plan -> Icons.Filled.DateRange
        Destination.Tasks -> Icons.AutoMirrored.Filled.List
    }
