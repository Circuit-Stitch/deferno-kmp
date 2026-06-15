package com.circuitstitch.deferno.shell

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.R
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.feature.braindumps.ui.InboxScreen
import com.circuitstitch.deferno.feature.calendar.ui.CalendarScreen
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.profile.ui.ProfileScreen
import com.circuitstitch.deferno.feature.settings.ui.SettingsScreen
import com.circuitstitch.deferno.feature.tasks.ui.SearchScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskDetailScreen
import com.circuitstitch.deferno.feature.tasks.ui.TasksScreen
import com.circuitstitch.deferno.shell.ui.ShellChrome
import com.circuitstitch.deferno.shell.ui.label

/**
 * The **Main shell** View (ADR-0013): the shared [ShellChrome] reveal drawer hosting the
 * [MainShellComponent]'s Destination graph, with a shell-level **overlay route** layered above it
 * (ADR-0015). The chrome's menu toggle slides the content aside to reveal the navigation menu
 * underneath; its top-bar actions open Brain dump (voice_chat) and New (add_task). The same chrome
 * renders the desktop shell (`:app:desktopApp`), so navigation looks and behaves identically across
 * platforms.
 */
@Composable
fun MainShell(component: MainShellComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    val overlay by component.overlay.subscribeAsState()
    val active = stack.active.instance

    // Drawer open/close is View state. System back closes it first; when closed this handler is
    // disabled and back falls through to the activity → RootComponent.onBackClicked() (the overlay /
    // inner-pane / Plan-home chain in MainShellComponent.onBack).
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    Box(modifier.fillMaxSize()) {
        ShellChrome(
            component = component,
            activeDestination = active.destination,
            drawerOpen = drawerOpen,
            onDrawerOpenChange = { drawerOpen = it },
            // The Calendar pre-dates New to its selected day (#74); elsewhere New is undated. Reads the
            // live active child at click time so the date stays the Calendar's concern, not the chrome's.
            onNew = {
                when (val a = component.stack.value.active.instance) {
                    is MainShellComponent.DestinationChild.Calendar -> a.component.onNewForSelectedDay()
                    else -> component.openOverlay(OverlayRoute.New())
                }
            },
            // Native res/drawable on Android (Robolectric doesn't serve a dependency module's
            // composeResources; the real app loads these fine either way) — see ShellChrome's KDoc.
            brainDumpIcon = painterResource(R.drawable.ic_voice_chat),
            newIcon = painterResource(R.drawable.ic_add_task),
            body = { DestinationBody(active, Modifier.fillMaxSize()) },
        )

        // The shell overlay route sits above the whole chrome (ADR-0015); back dismisses it first.
        overlay.child?.instance?.let { child ->
            OverlayHost(child = child, onDismiss = component::dismissOverlay)
        }
    }
}

/** Renders the foreground Destination's screen (or a coming-soon body for a not-yet-built Destination). */
@Composable
private fun DestinationBody(active: MainShellComponent.DestinationChild, modifier: Modifier = Modifier) {
    when (active) {
        is MainShellComponent.DestinationChild.Plan ->
            PlanBody(active, modifier)

        is MainShellComponent.DestinationChild.Calendar ->
            CalendarScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Tasks ->
            TasksScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Inbox ->
            InboxScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Profile ->
            ProfileScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Settings ->
            SettingsScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Placeholder ->
            ComingSoon(active.destination, modifier)
    }
}

/**
 * The Plan Destination's tier-3 stack (#51, ADR-0007 t3): the daily dashboard at the base, a drilled Task
 * detail above it — rendered INSIDE the chrome body (the drawer/top bar stay live), not as a shell overlay.
 * Each drill is a distinct child, so keying the detail on its component gives a fresh composition per Task
 * (no inherited scroll/focus from the parent — the bug the old overlay's `key` guarded against).
 */
@Composable
private fun PlanBody(plan: MainShellComponent.DestinationChild.Plan, modifier: Modifier = Modifier) {
    val stack by plan.stack.subscribeAsState()
    when (val child = stack.active.instance) {
        is MainShellComponent.PlanChild.Dashboard ->
            PlanScreen(child.component, modifier)

        is MainShellComponent.PlanChild.Detail ->
            key(child.component) { TaskDetailScreen(child.component, modifier) }
    }
}

/** A gentle placeholder body for a reserved-but-unbuilt Destination (the deferred Agenda/Dashboard/…, ADR-0015). */
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
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "This space is reserved — its features arrive in an upcoming release.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Renders the shell overlay route above the foreground Destination (ADR-0015): Search (#73), New (#71),
 * Feedback (#375), Brain dump (ADR-0027/#150 — the real dictation→Extractor surface on Android), and the
 * v1 [OverlayChild.Placeholder] stand-in.
 */
@Composable
private fun OverlayHost(child: MainShellComponent.OverlayChild, onDismiss: () -> Unit) {
    when (child) {
        MainShellComponent.OverlayChild.Placeholder ->
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Overlay",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "The shell overlay route is wired — Search and New render real content over it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.defernoColors.inkMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onDismiss, modifier = Modifier.padding(top = 24.dp)) {
                        Text("Dismiss")
                    }
                }
            }

        // The global Search overlay (#73) renders the real SearchScreen over the same primitive.
        is MainShellComponent.OverlayChild.Search ->
            SearchScreen(child.component, modifier = Modifier.fillMaxSize())

        // The New create surface (#71): the kind picker + per-kind form + online-only create.
        is MainShellComponent.OverlayChild.New ->
            NewScreen(child.component, Modifier.fillMaxSize())

        // The in-app Help → Feedback surface (#375): comment + file attachments, online-only submit.
        is MainShellComponent.OverlayChild.Feedback ->
            FeedbackScreen(child.component, Modifier.fillMaxSize())

        // Brain dump (ADR-0027/#150): continuous dictation → on-device Extractor → reviewable draft Tasks.
        is MainShellComponent.OverlayChild.BrainDump ->
            BrainDumpScreen(child.component, Modifier.fillMaxSize())
    }
}
