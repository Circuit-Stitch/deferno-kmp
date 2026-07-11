package com.circuitstitch.deferno.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
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
import com.circuitstitch.deferno.core.designsystem.resources.common_back
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
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import com.circuitstitch.deferno.feature.tasks.ui.SearchScreen
import com.circuitstitch.deferno.feature.tasks.ui.TaskDetailOverflowMenu
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

    // ADR-0044: a COMPACT Tasks detail (single-pane, with a detail foreground) makes the shell top bar a
    // drilled bar — ← back + a contextual overflow, no title, no search pill — computed HERE in the View so
    // the Compose-free `MainShellComponent`/`ChromeSpec` stay window-blind (§7 View-resolved). The predicate
    // is byte-identical to the one TasksScreen's ListDetailPaneScaffold uses, so the bar and the panes agree.
    val tasksComponent = (active as? MainShellComponent.DestinationChild.Tasks)?.component
    val compactDetail = rememberTasksCompactDetail(active)
    // ADR-0044: the foreground Plan-stack Task detail (if any) lifts its ⋮ overflow onto the shell bar so it
    // stays reachable when the detail body scrolls (the Plan drill's chrome is `drilled` = ← + no trailing).
    val planDetail = rememberPlanDetailComponent(active)

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
            // A compact Tasks detail drills the bar (← + overflow); else Tasks makes the search bar the
            // native top chrome (Files-style: ☰ inside the pill, magnifier trailing) — it owns the bar, so
            // its inline search band is dropped (TaskListScreen). Two-pane keeps the search pill (the detail
            // sits beside the tree, not drilled over it). Every other Destination = ☰ + title (null).
            topBarCenter = when {
                compactDetail && tasksComponent != null -> {
                    { TasksDetailDrilledBar(shell = component, tasks = tasksComponent) }
                }
                active.destination == Destination.Tasks -> {
                    { TasksSearchBar(onMenu = { drawerOpen = !drawerOpen }, onSearch = openSearch) }
                }
                else -> null
            },
            // The Plan-stack detail's ⋮ overflow rides the drilled bar's trailing edge (ADR-0044) so it stays
            // reachable when the body scrolls; null for every other surface (the Tasks detail's topBarCenter
            // bar carries its own overflow, and roots have none).
            topBarTrailing = if (planDetail != null) {
                { TaskDetailOverflowMenu(planDetail) }
            } else {
                null
            },
            // The drilled bar owns the ← + overflow, so suppress the bottom-centre capture FAB pair over the
            // read surface; every other surface keeps it (ADR-0044).
            showCaptureFabs = !compactDetail,
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

/**
 * Whether the active surface is a **compact Tasks detail** (ADR-0044): the Tasks Destination is foreground,
 * a detail is open, AND the window collapses to a single pane. The single-pane test is **byte-identical** to
 * the one `TasksScreen`'s `ListDetailPaneScaffold` uses (`calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth`
 * over `currentWindowAdaptiveInfo`, `maxHorizontalPartitions == 1`) so the drilled bar and the pane scaffold
 * never disagree about whether the detail is drilled-over (compact) or side-by-side (two-pane). Any non-Tasks
 * Destination short-circuits to false.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun rememberTasksCompactDetail(active: MainShellComponent.DestinationChild): Boolean {
    val tasks = (active as? MainShellComponent.DestinationChild.Tasks)?.component ?: return false
    val slot by tasks.detail.subscribeAsState()
    val singlePane = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo())
        .maxHorizontalPartitions == 1
    return slot.child != null && singlePane
}

/**
 * The LIVE foreground Plan-stack Task detail (#51), or null when the Plan dashboard — or any other Destination
 * — is foreground. The Plan drill's chrome is `drilled` (← + no title, ADR-0044), whose bar has no trailing
 * slot, so the View lifts the detail's ⋮ overflow into [ShellChrome]'s trailing slot to keep it reachable once
 * the body scrolls. Any non-Plan Destination short-circuits to null.
 */
@Composable
private fun rememberPlanDetailComponent(active: MainShellComponent.DestinationChild): TaskDetailComponent? {
    val plan = active as? MainShellComponent.DestinationChild.Plan ?: return null
    val stack by plan.stack.subscribeAsState()
    return (stack.active.instance as? MainShellComponent.PlanChild.Detail)?.component
}

/**
 * The **drilled Tasks-detail top bar** (ADR-0044): on a compact detail the shell bar carries a leading ← back
 * + a trailing contextual overflow, and NO title / search pill (the connected-parent node in the body is the
 * heading). Back reuses the same [MainShellComponent.onBack] the activity `BackHandler` and the drawer ← run
 * (it pops the foreground Tasks detail). The overflow binds to the LIVE foreground [TaskDetailComponent] read
 * off the detail slot; if it is momentarily null between activations, only the ← renders.
 */
@Composable
private fun TasksDetailDrilledBar(shell: MainShellComponent, tasks: TasksComponent) {
    val slot by tasks.detail.subscribeAsState()
    val detail = slot.child?.instance
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Reserve the ← from the OS back gesture so its taps aren't eaten at the left edge (as ShellTopBar does).
        IconButton(onClick = { shell.onBack() }, modifier = Modifier.systemGestureExclusionCompat()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back))
        }
        Spacer(Modifier.weight(1f))
        detail?.let { TaskDetailOverflowMenu(it) }
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
            // The ⋮ overflow now rides the shell's drilled bar (topBarTrailing) so it survives the body
            // scrolling (ADR-0044) — drop the body's own header kebab here to avoid doubling it.
            key(child.component) { TaskDetailScreen(child.component, modifier, showHeaderOverflow = false) }
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
