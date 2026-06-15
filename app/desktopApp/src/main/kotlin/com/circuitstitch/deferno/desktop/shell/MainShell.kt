package com.circuitstitch.deferno.desktop.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.ic_add_task
import com.circuitstitch.deferno.core.designsystem.resources.ic_voice_chat
import com.circuitstitch.deferno.feature.braindumps.ui.InboxDesktopScreen
import com.circuitstitch.deferno.feature.calendar.ui.CalendarDesktopScreen
import com.circuitstitch.deferno.feature.plan.ui.PlanDesktopScreen
import com.circuitstitch.deferno.feature.profile.ui.ProfileDesktopScreen
import com.circuitstitch.deferno.feature.settings.ui.SettingsDesktopScreen
import com.circuitstitch.deferno.feature.tasks.ui.SearchDesktopScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskDetailScreen
import com.circuitstitch.deferno.feature.tasks.ui.TasksDesktopScreen
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.MainShellComponent
import com.circuitstitch.deferno.shell.OverlayRoute
import com.circuitstitch.deferno.shell.ui.BrainDumpPlaceholder
import com.circuitstitch.deferno.shell.ui.ShellChrome
import com.circuitstitch.deferno.shell.ui.label
import org.jetbrains.compose.resources.painterResource

/**
 * The **Main shell** View, desktop edition (ADR-0013 / ADR-0017): it renders the **shared** [ShellChrome]
 * reveal drawer — the very chrome the Android shell uses (the #27 "Compose Views in a sibling Android+JVM
 * module" pattern applied to the shell) — over the [MainShellComponent]'s Destination graph, with the
 * shell-level **overlay route** ([OverlayHost]) layered above it (ADR-0015). The chrome's menu toggle
 * slides the content aside to reveal the navigation menu underneath; its top-bar actions open Brain dump
 * (voice_chat) and New (add_task).
 *
 * Desktop keeps its in-app menu bar + keyboard shortcuts (Main.kt) for desktop-class input; the reveal
 * drawer is the pointer-driven navigation surface alongside it. Width-driven rail↔drawer adaptation is
 * gone — the one chrome serves every window size.
 */
@Composable
fun MainShell(component: MainShellComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    val overlay by component.overlay.subscribeAsState()
    val active = stack.active.instance

    // Drawer open/close is View state. There is no system-back on desktop, so the drawer closes by
    // toggling the menu button or tapping the slid-aside content (ShellChrome); Esc stays the
    // overlay/pane back (Main.kt → RootComponent.onBackClicked()).
    var drawerOpen by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        ShellChrome(
            component = component,
            activeDestination = active.destination,
            drawerOpen = drawerOpen,
            onDrawerOpenChange = { drawerOpen = it },
            // The Calendar pre-dates New to its selected day (#74); elsewhere New is undated — the desktop
            // counterpart of the Android calendar FAB. Reads the live active child at click time.
            onNew = {
                when (val a = component.stack.value.active.instance) {
                    is MainShellComponent.DestinationChild.Calendar -> a.component.onNewForSelectedDay()
                    else -> component.openOverlay(OverlayRoute.New())
                }
            },
            // Desktop loads the shared composeResources glyphs off the JVM classpath (see ShellChrome KDoc).
            brainDumpIcon = painterResource(Res.drawable.ic_voice_chat),
            newIcon = painterResource(Res.drawable.ic_add_task),
            body = { DestinationContent(active) },
        )

        // The shell-level overlay route sits above the whole chrome (ADR-0015); shell back (Esc)
        // dismisses it first (routed through the shared MainShellComponent.onBack()).
        overlay.child?.instance?.let { child ->
            OverlayHost(child = child, onDismiss = component::dismissOverlay)
        }
    }
}

/**
 * Renders the foreground Destination's desktop screen, filling the content area. Plan + Tasks render
 * their existing desktop Views, Settings (#85) its tier-3 drill-down, Profile (#84) its identity hub,
 * and Calendar (#74) its month grid + day agenda (two-pane on a wide window) — every v1 Destination has
 * a native desktop View (ADR-0017).
 */
@Composable
private fun DestinationContent(active: MainShellComponent.DestinationChild) {
    when (active) {
        is MainShellComponent.DestinationChild.Plan ->
            PlanDesktopScreen(active.component, Modifier.fillMaxSize())

        is MainShellComponent.DestinationChild.Tasks ->
            TasksDesktopScreen(active.component, Modifier.fillMaxSize())

        // The Inbox Destination's desktop View (ADR-0015 Inbox amendment): the Brain dump draft review queue.
        is MainShellComponent.DestinationChild.Inbox ->
            InboxDesktopScreen(active.component, Modifier.fillMaxSize())

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
 * The shell-level overlay above the foreground Destination (ADR-0015): Search (#86) and New (#87) render
 * their real desktop Views, Feedback (#375) the comment + AWT file-attach surface, Brain dump (ADR-0027)
 * the shared placeholder, and the v1 [OverlayChild.Placeholder] a dismissible stand-in. Each is dismissed
 * by shell back (Esc) / its own Close affordance.
 */
@Composable
private fun OverlayHost(child: MainShellComponent.OverlayChild, onDismiss: () -> Unit) {
    when (child) {
        is MainShellComponent.OverlayChild.Search ->
            SearchDesktopScreen(child.component, Modifier.fillMaxSize())

        is MainShellComponent.OverlayChild.New ->
            NewDesktopScreen(child.component, Modifier.fillMaxSize())

        // The in-app Help → Feedback surface (#375): comment + file attachments (AWT file dialog).
        is MainShellComponent.OverlayChild.Feedback ->
            FeedbackDesktopScreen(child.component, Modifier.fillMaxSize())

        // A Plan tap (#51): the Task's detail over the dashboard, instead of switching to the Tasks Destination.
        is MainShellComponent.OverlayChild.TaskDetail ->
            TaskDetailScreen(child.component, Modifier.fillMaxSize())

        // Brain dump (ADR-0027): the dictation-driven Extractor ships on Android first (the on-device
        // shacl floor is Android-only); desktop keeps the placeholder until a JVM engine lands.
        is MainShellComponent.OverlayChild.BrainDump ->
            BrainDumpPlaceholder(onDismiss = onDismiss)

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
