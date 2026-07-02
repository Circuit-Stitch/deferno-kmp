package com.circuitstitch.deferno.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.R
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_dismiss
import com.circuitstitch.deferno.core.designsystem.resources.common_search
import com.circuitstitch.deferno.core.designsystem.resources.shell_coming_soon_body
import com.circuitstitch.deferno.core.designsystem.resources.shell_coming_soon_title
import com.circuitstitch.deferno.core.designsystem.resources.shell_menu_cd
import com.circuitstitch.deferno.core.designsystem.resources.shell_overlay_placeholder_body
import com.circuitstitch.deferno.core.designsystem.resources.shell_overlay_placeholder_title
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.feature.braindumps.ui.InboxScreen
import com.circuitstitch.deferno.feature.calendar.ui.CalendarScreen
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.profile.ui.ProfileScreen
import com.circuitstitch.deferno.feature.settings.ui.SettingsScreen
import com.circuitstitch.deferno.feature.tasks.ui.SearchScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskDetailScreen
import com.circuitstitch.deferno.feature.tasks.ui.TasksScreen
import com.circuitstitch.deferno.shell.ui.ActivityScreen
import com.circuitstitch.deferno.shell.ui.ShellChrome
import com.circuitstitch.deferno.shell.ui.label
import com.circuitstitch.deferno.shell.ui.systemGestureExclusionCompat
import org.jetbrains.compose.resources.stringResource

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

    val openSearch = { component.openOverlay(OverlayRoute.Search()) }

    Box(modifier.fillMaxSize()) {
        ShellChrome(
            component = component,
            activeDestination = active.destination,
            drawerOpen = drawerOpen,
            onDrawerOpenChange = { drawerOpen = it },
            // Native res/drawable on Android (Robolectric doesn't serve a dependency module's
            // composeResources; the real app loads these fine either way) — see ShellChrome's KDoc.
            brainDumpIcon = painterResource(R.drawable.ic_voice_chat),
            newIcon = painterResource(R.drawable.ic_add_task),
            // Tasks makes the search bar the native top chrome (Files-style: ☰ inside the pill, magnifier
            // trailing). It owns the bar, so its inline search band is dropped (TaskListScreen). end padding
            // keeps the pill clear of the trailing capture FAB pair.
            topBarCenter = if (active.destination == Destination.Tasks) {
                { TasksSearchBar(onMenu = { drawerOpen = !drawerOpen }, onSearch = openSearch) }
            } else {
                null
            },
            body = { DestinationBody(active, openSearch, Modifier.fillMaxSize()) },
        )

        // The shell overlay route sits above the whole chrome (ADR-0015); back dismisses it first.
        overlay.child?.instance?.let { child ->
            OverlayHost(child = child, onDismiss = component::dismissOverlay)
        }
    }
}

/**
 * The Tasks **search-as-top-bar** (Files pattern): a full-width pill that IS the bar — the ☰ menu inside
 * it on the left, a "Search" placeholder, a trailing magnifier — making search read as the native top
 * chrome rather than a row below a title. Tapping the pill opens the Search overlay; the leading ☰ toggles
 * the drawer. Spans the full bar now that the capture FABs sit bottom-centre (ShellCaptureFabs).
 */
@Composable
private fun TasksSearchBar(onMenu: () -> Unit, onSearch: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(26.dp),
        // A fixed pill height — NOT heightIn(min): the bar Row passes an open max-height down, so a
        // min-only height lets the inner fillMaxHeight expand the pill to fill the screen (eats the body).
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 8.dp, end = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Reserve the ☰ from the OS back gesture so its taps aren't eaten at the left edge after a
            // screen wake (the same fix as ShellTopBar's leading control).
            IconButton(onClick = onMenu, modifier = Modifier.systemGestureExclusionCompat()) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = stringResource(Res.string.shell_menu_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val search = stringResource(Res.string.common_search)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Button, onClickLabel = search, onClick = onSearch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = search,
                    color = MaterialTheme.defernoColors.inkMuted,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.defernoColors.inkMuted,
                    modifier = Modifier.padding(end = 14.dp).size(20.dp),
                )
            }
        }
    }
}

/** Renders the foreground Destination's screen (or a coming-soon body for a not-yet-built Destination). */
@Composable
private fun DestinationBody(
    active: MainShellComponent.DestinationChild,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (active) {
        is MainShellComponent.DestinationChild.Plan ->
            PlanBody(active, modifier)

        is MainShellComponent.DestinationChild.Calendar ->
            CalendarScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Tasks ->
            TasksScreen(active.component, modifier, onSearch = onSearch)

        // The Assistant chat ships first as iOS SwiftUI (ADR-0040); the Android Compose View is deferred, so
        // this is a placeholder. The Destination's row is also hidden on Android (the inert client → not
        // entitled), so this arm is reached only if that gating changes — kept for `when` totality.
        is MainShellComponent.DestinationChild.Assistant ->
            ComingSoon(active.destination, modifier)

        is MainShellComponent.DestinationChild.Inbox ->
            InboxScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Profile ->
            ProfileScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Settings ->
            SettingsScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Activity ->
            ActivityScreen(active.component, modifier)

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
            text = stringResource(Res.string.shell_coming_soon_title, destination.label),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(Res.string.shell_coming_soon_body),
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
                        text = stringResource(Res.string.shell_overlay_placeholder_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(Res.string.shell_overlay_placeholder_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.defernoColors.inkMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onDismiss, modifier = Modifier.padding(top = 24.dp)) {
                        Text(stringResource(Res.string.common_dismiss))
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

        // Breakdown (Deferno#525): the on-device "what's stopping you?" impediment chat over one stuck
        // item, opened from the Task detail kebab. The Compose render of the deterministic engine + the
        // on-device classifier (the Android twin of iOS's SwiftUI surface).
        is MainShellComponent.OverlayChild.Breakdown ->
            BreakdownScreen(child.component, Modifier.fillMaxSize())
    }
}
