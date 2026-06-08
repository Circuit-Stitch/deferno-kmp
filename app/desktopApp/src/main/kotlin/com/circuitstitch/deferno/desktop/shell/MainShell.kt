package com.circuitstitch.deferno.desktop.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.feature.calendar.ui.CalendarDesktopScreen
import com.circuitstitch.deferno.feature.plan.ui.PlanDesktopScreen
import com.circuitstitch.deferno.feature.profile.ui.ProfileDesktopScreen
import com.circuitstitch.deferno.feature.settings.ui.SettingsDesktopScreen
import com.circuitstitch.deferno.feature.tasks.ui.SearchDesktopScreen
import com.circuitstitch.deferno.feature.tasks.ui.TasksDesktopScreen
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.MainShellComponent

/**
 * The **Main shell** View, desktop edition (ADR-0013 / ADR-0017): it hosts the shared
 * [MainShellComponent]'s Destination graph in a desktop-native navigation surface. Rather than reuse
 * the Android `NavigationSuiteScaffold` (an Android-only adaptive artifact), it renders the desktop's
 * own nav suite — a persistent [NavigationRail] that **expands into a [PermanentNavigationDrawer]** on a
 * wide window. It lists **every** registered [Destination] directly (the `NavSlot` Primary/Secondary
 * distinction is a no-op on desktop — there is no compact "More") and renders the foreground
 * Destination's screen as content, with the shell-level **overlay route** layered above it (ADR-0015).
 *
 * The rail ↔ drawer choice is driven purely by the **continuous available width** (ADR-0008 G1 — never
 * a device-type check) via [desktopNavKindFor]; the View is otherwise a pure renderer of the shared
 * stack, so resizing across the breakpoint never drops state (G5).
 */
@Composable
fun MainShell(component: MainShellComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    val overlay by component.overlay.subscribeAsState()
    val active = stack.active.instance
    Box(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
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

        // The shell-level overlay route sits above the whole nav suite (ADR-0015); shell back (Esc)
        // dismisses it first (routed through the shared MainShellComponent.onBack()).
        overlay.child?.instance?.let { child ->
            OverlayHost(child = child, onDismiss = component::dismissOverlay)
        }
    }
}

/**
 * Renders the foreground Destination's desktop screen, filling the content area. Plan + Tasks render
 * their existing desktop Views, Settings (#85) its tier-3 drill-down, Profile (#84) its identity hub,
 * and Calendar (#74) its month grid + day agenda (two-pane on a wide window) — every v1 Destination now
 * has a native desktop View (ADR-0017).
 */
@Composable
private fun DestinationContent(active: MainShellComponent.DestinationChild) {
    when (active) {
        is MainShellComponent.DestinationChild.Plan ->
            PlanDesktopScreen(active.component, Modifier.fillMaxSize())

        is MainShellComponent.DestinationChild.Tasks ->
            TasksDesktopScreen(active.component, Modifier.fillMaxSize())

        // The Calendar Destination's desktop View (#74): the desktop counterpart of the Android screen.
        is MainShellComponent.DestinationChild.Calendar ->
            CalendarDesktopScreen(active.component, Modifier.fillMaxSize())

        is MainShellComponent.DestinationChild.Profile ->
            ProfileDesktopScreen(active.component, Modifier.fillMaxSize())

        is MainShellComponent.DestinationChild.Settings ->
            SettingsDesktopScreen(active.component, Modifier.fillMaxSize())

        is MainShellComponent.DestinationChild.Placeholder ->
            ComingSoon(active.destination)
    }
}

/**
 * The shell-level overlay above the foreground Destination (ADR-0015): an opaque surface layered over
 * the whole nav suite, dismissed first by shell back (Esc). Search (#86) and New (#87) now render their
 * real desktop Views — each an opaque [Surface] that owns its own Close/Cancel affordance (routed back
 * through the shared component's dismiss Output); the v1 [OverlayChild.Placeholder] route (the shared
 * mechanism's stand-in, not opened on desktop) keeps a dismissible placeholder. Overlays are reachable
 * via the View menu + Ctrl+F/Ctrl+N (Main.kt).
 */
@Composable
private fun OverlayHost(child: MainShellComponent.OverlayChild, onDismiss: () -> Unit) {
    when (child) {
        is MainShellComponent.OverlayChild.Search ->
            SearchDesktopScreen(child.component, Modifier.fillMaxSize())

        is MainShellComponent.OverlayChild.New ->
            NewDesktopScreen(child.component, Modifier.fillMaxSize())

        MainShellComponent.OverlayChild.Placeholder ->
            OverlayPlaceholder(onDismiss)
    }
}

/** The dismissible stand-in for the v1 [MainShellComponent.OverlayChild.Placeholder] route (ADR-0015). */
@Composable
private fun OverlayPlaceholder(onDismiss: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "Overlay", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "This surface arrives on desktop in an upcoming release.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 24.dp)) {
                Text("Dismiss")
            }
        }
    }
}

/** A gentle placeholder body for a Destination whose desktop View isn't built yet (ADR-0017). */
@Composable
private fun ComingSoon(destination: Destination, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "${destination.label} is coming soon",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "This space is reserved — its desktop view arrives in an upcoming release.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
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
        Destination.Calendar -> "Calendar"
        Destination.Tasks -> "Tasks"
        Destination.Profile -> "Profile"
        Destination.Settings -> "Settings"
    }

/** The nav-suite icon for a [Destination] — a View concern, kept out of the shared registry. */
private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Plan -> Icons.Filled.Home
        Destination.Calendar -> Icons.Filled.DateRange
        Destination.Tasks -> Icons.AutoMirrored.Filled.List
        Destination.Profile -> Icons.Filled.Person
        Destination.Settings -> Icons.Filled.Settings
    }
