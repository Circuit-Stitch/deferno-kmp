package com.circuitstitch.deferno.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.tasks.ui.TasksScreen

/**
 * The **Main shell** View (ADR-0013): a Material 3 `NavigationSuiteScaffold` hosting the
 * [MainShellComponent]'s Destination graph. It renders one nav-suite item per registered
 * [Destination] (never a fixed count) and the foreground Destination's screen as content. The nav
 * suite adapts bottom-bar → rail → drawer purely by window size class (see [navigationSuiteTypeFor]).
 */
@Composable
fun MainShell(component: MainShellComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    val active = stack.active.instance
    NavigationSuiteScaffold(
        modifier = modifier,
        layoutType = navigationSuiteTypeFor(currentWindowAdaptiveInfo().windowSizeClass),
        navigationSuiteItems = {
            component.destinations.forEach { destination ->
                item(
                    selected = destination == active.destination,
                    onClick = { component.selectDestination(destination) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(destination.label) },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // The dev account switcher (#68, ADR-0014) — shown only with more than one Account, so the
            // common single-account case is visually unchanged. Switching re-keys the whole shell for
            // the new Account (no re-auth, the PAT is already vaulted).
            val accounts by component.accounts.collectAsState()
            val activeAccount by component.activeAccount.collectAsState()
            if (accounts.size > 1) {
                AccountSwitcher(
                    accounts = accounts,
                    active = activeAccount,
                    onSwitch = component::switchAccount,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (active) {
                    is MainShellComponent.DestinationChild.Plan ->
                        PlanScreen(active.component, Modifier.fillMaxSize())

                    is MainShellComponent.DestinationChild.Tasks ->
                        TasksScreen(active.component, Modifier.fillMaxSize())
                }
            }
        }
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
        Destination.Tasks -> "Tasks"
    }

/** The nav-suite icon for a [Destination] — a View concern, kept out of the shared registry. */
private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Plan -> Icons.Filled.DateRange
        Destination.Tasks -> Icons.AutoMirrored.Filled.List
    }
