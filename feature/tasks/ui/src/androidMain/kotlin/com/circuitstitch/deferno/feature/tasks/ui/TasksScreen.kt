package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_close
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_pane_empty_body
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_pane_empty_title
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import org.jetbrains.compose.resources.stringResource

/**
 * The Tasks feature host — the **adaptive tier-2 Pane layout** for the Tasks Destination (#29, ADR-0007).
 * Since ADR-0034 the primary pane is the nested **Item tree** ([TasksComponent.tree]) — the old flat list +
 * one-level drill pane are subsumed — and the secondary pane is the lone Task [TasksComponent.detail]. It
 * renders them as **one or two panes by window size class** via M3 `ListDetailPaneScaffold`: the tree on
 * compact width, a side-by-side tree + detail on regular/expanded width.
 *
 * **Adaptive off continuous window metrics, never device checks (ADR-0008 G1)** via
 * [calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth] over [currentWindowAdaptiveInfo]. **Resizable
 * without losing pane state (ADR-0008 G5):** the detail slot lives in the (retained) shared component, so
 * the scaffold's `value` is derived from component state on every composition — crossing the breakpoint
 * never drops what's open. In a single pane the scaffold shows the detail when one is open, else the tree.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun TasksScreen(
    component: TasksComponent,
    modifier: Modifier = Modifier,
    // Hoisted by the shell so it can dock a compact search into the top bar on scroll; [onSearch] opens the
    // shell Search overlay (no-op default for callers that don't wire it).
    listState: LazyListState = rememberLazyListState(),
    onSearch: () -> Unit = {},
) {
    val detailSlot by component.detail.subscribeAsState()
    val detail = detailSlot.child?.instance

    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo())
    // Which pane the single-pane (compact) collapse shows: the detail when one is open, else the tree. In
    // two panes both are visible, so this only governs the compact fold.
    val foreground =
        if (detail == null) ListDetailPaneScaffoldRole.List else ListDetailPaneScaffoldRole.Detail
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
        listPane = { AnimatedPane { TaskListScreen(component.tree, listState = listState, onSearch = onSearch) } },
        // Two-pane only (the compact fold's back is owned by the shell drilled bar, ADR-0044): the detail
        // sits beside the tree with no drilled shell ← over it, so it renders its own close affordance.
        detailPane = {
            AnimatedPane { TasksDetailPane(detail, showClose = directive.maxHorizontalPartitions > 1) }
        },
    )
}

/**
 * The detail (secondary) pane: the Task detail when one is open, else a gentle placeholder (the two-pane
 * "pick a task" state). The desktop screen renders the same choice in its own layout. On two-pane
 * ([showClose]) the detail carries a trailing **close ×** (wired to [TaskDetailComponent.onCloseClicked]) —
 * the compact fold's back lives in the shell drilled bar instead, so this affordance would double there
 * (ADR-0044, PaneHeader removed).
 */
@Composable
private fun TasksDetailPane(
    detail: TaskDetailComponent?,
    showClose: Boolean,
    modifier: Modifier = Modifier,
) {
    if (detail != null) {
        if (showClose) {
            Column(modifier) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = detail::onCloseClicked) {
                        Icon(DefernoIcons.Close, contentDescription = stringResource(Res.string.common_close))
                    }
                }
                TaskDetailScreen(detail)
            }
        } else {
            // Compact single-pane: the shell drilled bar owns the ⋮ overflow, so the body suppresses its own.
            TaskDetailScreen(detail, modifier, showHeaderOverflow = false)
        }
    } else {
        EmptyState(
            title = stringResource(Res.string.tasks_detail_pane_empty_title),
            body = stringResource(Res.string.tasks_detail_pane_empty_body),
            modifier = modifier,
        )
    }
}
