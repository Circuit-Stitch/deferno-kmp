package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskTreeComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent

/**
 * The Tasks feature host — the **adaptive tier-2 Pane layout** for the Tasks Destination (#29,
 * ADR-0007). It renders the shared component's co-resident list/detail/tree slots as **one or two
 * panes by window size class** via Material 3 `ListDetailPaneScaffold`: a single pane on compact
 * width, a side-by-side list + detail/tree on regular/expanded width.
 *
 * **Adaptive off continuous window metrics, never device checks (ADR-0008 G1).** The pane count comes
 * from [calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth] over [currentWindowAdaptiveInfo] —
 * the M3 "two panes from the medium (regular) width up, one on compact" directive, which is exactly
 * the issue's "two-pane on expanded/regular, single on compact" rule. No `isTablet`/`isPad` anywhere.
 *
 * **Resizable without losing pane state (ADR-0008 G5).** All UI state lives in the (retained) shared
 * component — [TasksComponent.detail]/[TasksComponent.tree] are co-resident slots and
 * [TasksComponent.activePane] is their foreground recency — so the scaffold's `value` is *derived from
 * component state on every composition*. Crossing the breakpoint (or any arbitrary resize) therefore
 * never drops what's open: this View holds no foreground state of its own.
 *
 * In a single pane the scaffold collapses to the **most-recently-foregrounded** slot, falling back to
 * the list — the shared [resolveSecondarySlot] precedence the desktop screen ([TasksDesktopScreen])
 * uses too. Because detail and tree are co-resident (both can be open at once), a fixed precedence
 * would mis-foreground a tree→child drill-in — the bug the host's drill-in regression test guards.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun TasksScreen(component: TasksComponent, modifier: Modifier = Modifier) {
    val detailSlot by component.detail.subscribeAsState()
    val treeSlot by component.tree.subscribeAsState()
    val activePane by component.activePane.subscribeAsState()

    val detail = detailSlot.child?.instance
    val tree = treeSlot.child?.instance

    // The shared secondary-pane precedence (#67): which co-resident slot the detail pane shows. The
    // same helper drives the desktop screen, so both platforms foreground the same slot.
    val slot = resolveSecondarySlot(activePane, hasDetail = detail != null, hasTree = tree != null)

    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo())
    // Which pane the single-pane (compact) collapse shows: the detail/tree whenever a co-resident slot
    // is open ([slot] != None), else the list. In two panes both are visible, so this only governs the
    // compact fold. Derived from [slot] so the scaffold's choice and the pane's content can't disagree.
    val foreground =
        if (slot == SecondarySlot.None) ListDetailPaneScaffoldRole.List else ListDetailPaneScaffoldRole.Detail
    val scaffoldValue = calculateThreePaneScaffoldValue(
        maxHorizontalPartitions = directive.maxHorizontalPartitions,
        adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
        // contentKey is unused (the component, not the scaffold, holds which task is open), so the
        // destination only carries the foreground pane role.
        currentDestination = ThreePaneScaffoldDestinationItem<Nothing>(foreground),
    )

    ListDetailPaneScaffold(
        directive = directive,
        value = scaffoldValue,
        modifier = modifier,
        listPane = { AnimatedPane { TaskListScreen(component.list) } },
        detailPane = { AnimatedPane { TasksDetailPane(slot, detail, tree) } },
    )
}

/**
 * The detail (secondary) pane: renders the slot the shared [resolveSecondarySlot] precedence chose, or
 * a gentle placeholder when nothing is open (the two-pane "pick a task" state). The desktop screen maps
 * the same [SecondarySlot] to its own panes, so both platforms foreground the same slot.
 */
@Composable
private fun TasksDetailPane(
    slot: SecondarySlot,
    detail: TaskDetailComponent?,
    tree: TaskTreeComponent?,
    modifier: Modifier = Modifier,
) {
    when (slot) {
        // The helper only returns Tree/Detail when that slot is open, so the instance is non-null here.
        SecondarySlot.Tree -> tree?.let { TaskTreeScreen(it, modifier) }
        SecondarySlot.Detail -> detail?.let { TaskDetailScreen(it, modifier) }
        SecondarySlot.None -> EmptyState(
            title = "Nothing open",
            body = "Pick a task on the left to see its details here.",
            modifier = modifier,
        )
    }
}
