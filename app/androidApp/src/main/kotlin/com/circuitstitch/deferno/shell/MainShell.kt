package com.circuitstitch.deferno.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.window.core.layout.WindowSizeClass
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.tasks.ui.TasksScreen
import kotlinx.datetime.LocalDate

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
        when (active) {
            is MainShellComponent.DestinationChild.Plan ->
                PlanScreen(active.component, Modifier.fillMaxSize())

            is MainShellComponent.DestinationChild.Tasks ->
                TasksScreen(active.component, Modifier.fillMaxSize())
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

@Preview
@Composable
private fun MainShellPreview() {
    // A real Main shell over the in-memory stub data, so the preview shows the nav suite + the Plan
    // Destination it opens into. (@Preview is IDE-only — never executed by the build or tests.)
    val component = remember {
        DefaultMainShellComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            taskRepository = DemoTaskRepository(SampleData.tasks),
            planRepository = DemoPlanRepository(
                SampleData.planTaskIds.mapNotNull { id -> SampleData.tasks.firstOrNull { it.id == id } },
            ),
            today = LocalDate(2026, 6, 6),
            timeZone = "UTC",
        )
    }
    DefernoTheme { MainShell(component) }
}
