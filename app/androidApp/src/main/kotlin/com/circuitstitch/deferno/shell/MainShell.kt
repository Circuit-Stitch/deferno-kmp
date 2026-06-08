package com.circuitstitch.deferno.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.feature.calendar.ui.CalendarScreen
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.profile.ui.ProfileScreen
import com.circuitstitch.deferno.feature.settings.ui.SettingsScreen
import com.circuitstitch.deferno.feature.tasks.ui.SearchScreen
import com.circuitstitch.deferno.feature.tasks.ui.TasksScreen

/**
 * The **Main shell** View (ADR-0013): a Material 3 `NavigationSuiteScaffold` hosting the
 * [MainShellComponent]'s Destination graph, with a shell-level **overlay route** layered above it
 * (ADR-0015). It renders the adaptive nav suite ([navSuiteLayoutFor]) — on a **compact** window the
 * bottom bar shows the primary Destinations plus a **"More"** overflow onto the secondary ones; on
 * **medium/expanded** the rail/drawer lists every Destination directly and "More" disappears — and the
 * foreground Destination's screen as content. The nav suite adapts bottom-bar → rail → drawer purely by
 * window size class (see [navigationSuiteTypeFor]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(component: MainShellComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    val overlay by component.overlay.subscribeAsState()
    val active = stack.active.instance

    val navType = navigationSuiteTypeFor(currentWindowAdaptiveInfo().windowSizeClass)
    val layout = navSuiteLayoutFor(component.destinations, navType)
    var moreOpen by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            layoutType = navType,
            navigationSuiteItems = {
                layout.items.forEach { destination ->
                    item(
                        selected = destination == active.destination,
                        onClick = { component.selectDestination(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
                if (layout.showMore) {
                    item(
                        // "More" reads as selected while a secondary Destination is foreground on compact.
                        selected = active.destination in layout.overflow,
                        onClick = { moreOpen = true },
                        icon = { Icon(Icons.Filled.MoreVert, contentDescription = null) },
                        label = { Text("More") },
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                ShellTopBar(
                    accounts = component.accounts,
                    activeAccount = component.activeAccount,
                    onSwitch = component::switchAccount,
                    onSearch = { component.openOverlay(OverlayRoute.Search) },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    DestinationBody(active, Modifier.fillMaxSize())
                    // The shell-level New FAB (#71, ADR-0015 "launched from any FAB"): opens the New
                    // create overlay above the foreground Destination. Shell-level so it is available
                    // from every Destination without each slice owning its own.
                    FloatingActionButton(
                        // On the Calendar, the FAB opens New pre-dated to the selected day (#74); elsewhere
                        // it opens an undated New. The Calendar routes its selected day through its own
                        // component so the date stays the Destination's concern, not the shell chrome's.
                        onClick = {
                            when (val a = active) {
                                is MainShellComponent.DestinationChild.Calendar -> a.component.onNewForSelectedDay()
                                else -> component.openOverlay(OverlayRoute.New())
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New")
                    }
                }
            }
        }

        // The shell overlay route sits above the whole nav suite (ADR-0015); back dismisses it first.
        overlay.child?.instance?.let { child ->
            OverlayHost(child = child, onDismiss = component::dismissOverlay)
        }
    }

    if (moreOpen) {
        ModalBottomSheet(onDismissRequest = { moreOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    text = "More",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp).semantics { heading() },
                )
                layout.overflow.forEach { destination ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(onClickLabel = "Open ${destination.label}") {
                                component.selectDestination(destination)
                                moreOpen = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(destination.icon, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Text(destination.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/**
 * The slim shell chrome above the Destination content: the global Search (⌕) affordance — which pushes
 * the shell overlay route (ADR-0015) — and the Active-Account switcher (shown only with more than one
 * Account). The per-Destination title lives in each screen's own header, so this stays minimal.
 */
@Composable
private fun ShellTopBar(
    accounts: kotlinx.coroutines.flow.StateFlow<List<Account>>,
    activeAccount: kotlinx.coroutines.flow.StateFlow<Account?>,
    onSwitch: (AccountId) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accountList by accounts.collectAsState()
    val active by activeAccount.collectAsState()
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accountList.size > 1) {
            AccountSwitcher(accounts = accountList, active = active, onSwitch = onSwitch)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search")
        }
    }
}

/** Renders the foreground Destination's screen (or a coming-soon body for a not-yet-built Destination). */
@Composable
private fun DestinationBody(active: MainShellComponent.DestinationChild, modifier: Modifier = Modifier) {
    when (active) {
        is MainShellComponent.DestinationChild.Plan ->
            PlanScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Calendar ->
            CalendarScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Tasks ->
            TasksScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Profile ->
            ProfileScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Settings ->
            SettingsScreen(active.component, modifier)

        is MainShellComponent.DestinationChild.Placeholder ->
            ComingSoon(active.destination, modifier)
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
 * Renders the shell overlay route above the foreground Destination (ADR-0015). v1 carries only the
 * [MainShellComponent.OverlayChild.Placeholder] — an opaque, dismissible surface proving the mechanism;
 * Search (#73) and New (#71) supply real content over the same primitive.
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
    }
}

/**
 * The Active-Account selector (#68, ADR-0014): a dropdown of the device's Accounts. Picking one calls
 * [MainShellComponent.switchAccount], which re-keys the shell for that Account — fast user switching
 * with no re-auth (ADR-0002/0012).
 */
@Composable
private fun AccountSwitcher(
    accounts: List<Account>,
    active: Account?,
    onSwitch: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(active?.label ?: "Select account")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch account")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.label) },
                    onClick = {
                        expanded = false
                        if (account.id != active?.id) onSwitch(account.id)
                    },
                )
            }
        }
    }
}

/**
 * How the registered Destinations split across the adaptive nav suite (ADR-0015). Pure + internal so
 * the compact "More" overflow partition is unit-tested directly, without Robolectric.
 *
 * - On a **compact** window (bottom bar): [items] are the [NavSlot.Primary] Destinations, [overflow]
 *   the [NavSlot.Secondary] ones reached via a "More" launcher ([showMore]).
 * - On **medium/expanded** (rail/drawer): every Destination is a direct [item]; there is no "More".
 */
internal data class NavSuiteLayout(
    val items: List<Destination>,
    val overflow: List<Destination>,
    val showMore: Boolean,
)

internal fun navSuiteLayoutFor(
    destinations: List<Destination>,
    navType: NavigationSuiteType,
): NavSuiteLayout =
    if (navType == NavigationSuiteType.NavigationBar) {
        val overflow = destinations.filter { it.slot == NavSlot.Secondary }
        NavSuiteLayout(
            items = destinations.filter { it.slot == NavSlot.Primary },
            overflow = overflow,
            showMore = overflow.isNotEmpty(),
        )
    } else {
        NavSuiteLayout(items = destinations, overflow = emptyList(), showMore = false)
    }

/**
 * Map the continuous window-width metric to the nav-suite layout (ADR-0008 G1 / ADR-0007 size-class
 * adaptation): bottom bar (compact) → rail (medium ≥ 600dp) → drawer (expanded ≥ 840dp). Reads only
 * window-size-class breakpoints — never device-type checks (`isIPad`/`isTablet`). Pure + internal so
 * the breakpoint mapping is unit-tested directly.
 */
internal fun navigationSuiteTypeFor(windowSizeClass: WindowSizeClass): NavigationSuiteType =
    when {
        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ->
            NavigationSuiteType.NavigationDrawer

        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
            NavigationSuiteType.NavigationRail

        else -> NavigationSuiteType.NavigationBar
    }

/** The nav-suite label for a [Destination] — a View concern, kept out of the shared registry. */
private val Destination.label: String
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
