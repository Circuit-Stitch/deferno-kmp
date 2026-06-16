package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.feature.tasks.ItemTreeComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent

/**
 * The Tasks screen, desktop edition — the desktop counterpart of the adaptive Android `TasksScreen` (#29).
 * Both render the component's panes as **1 or 2 panes by window size class** (ADR-0007 tier-2): Android via
 * M3 `ListDetailPaneScaffold`, desktop via `BoxWithConstraints` here. Since ADR-0034 the primary pane is the
 * nested **Item tree** ([TasksComponent.tree]) — the flat list + one-level drill pane are subsumed — and the
 * secondary pane is the lone Task [TasksComponent.detail].
 *
 * It is adaptive off the continuous available width (ADR-0008 G1 — never a device-type check): at
 * [TasksTwoPaneMinWidth]+ it shows two panes (tree + detail); narrower, it collapses to a single pane. The
 * component owns all state, so resizing across the breakpoint never drops what's open (G5). It reuses the
 * slice's shared commonMain atoms ([ItemTreeContent], [PaneHeader], [WorkingStateBadge], …).
 */
@Composable
fun TasksDesktopScreen(component: TasksComponent, modifier: Modifier = Modifier) {
    val detailSlot by component.detail.subscribeAsState()
    val detail = detailSlot.child?.instance

    // The shared secondary-pane precedence (#67/#227), identical to the Android screen's (TasksScreen.kt):
    // the detail pane shows the detail when one is open. Only the None fallback differs by layout — an empty
    // "pick a task" state beside the two-pane tree, the tree itself in the single-pane fold.
    val slot = resolveSecondarySlot(hasDetail = detail != null)

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= TasksTwoPaneMinWidth) {
            // Two panes: the tree is always present on the left; the right pane is the resolved slot.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(TasksListPaneWidth).fillMaxHeight()) {
                    TreePane(component.tree)
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    SecondaryPane(slot, detail)
                }
            }
        } else {
            // One pane: render the detail when open, else the tree (the home state when nothing is open).
            when (slot) {
                SecondarySlot.Detail -> detail?.let { TaskDetailScreen(it) }
                SecondarySlot.None -> TreePane(component.tree)
            }
        }
    }
}

/** Below this available width the desktop Tasks screen collapses to a single pane (ADR-0007 tier-2). */
internal val TasksTwoPaneMinWidth = 720.dp

/** Fixed width of the tree pane in the two-pane layout; the secondary pane takes the rest. */
private val TasksListPaneWidth = 360.dp

/** The right-hand pane in the two-pane layout: the resolved Task detail, else a gentle placeholder. */
@Composable
private fun SecondaryPane(slot: SecondarySlot, detail: TaskDetailComponent?) {
    when (slot) {
        // The helper only returns Detail when the slot is open, so the instance is non-null here.
        SecondarySlot.Detail -> detail?.let { TaskDetailScreen(it) }
        SecondarySlot.None -> EmptyState(
            title = "Nothing open",
            body = "Pick a task on the left to see its details here.",
        )
    }
}

/** The Item-tree pane — a thin renderer of [ItemTreeComponent], reusing the shared [ItemTreeContent]. */
@Composable
private fun TreePane(component: ItemTreeComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    ItemTreeContent(
        rows = state.rows,
        isRefreshing = state.isRefreshing,
        onToggleExpand = component::onToggleExpand,
        onOpenDetail = component::onOpenDetail,
        onRefresh = component::onRefresh,
        modifier = modifier,
    )
}

/** The detail pane — a thin renderer of [TaskDetailComponent]; the component hydrates on creation (#22).
 *  Public so the shell can also render it as a Plan-tap overlay (#51), not just inside the Tasks pane. */
@Composable
fun TaskDetailScreen(component: TaskDetailComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val task = state.task
    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(title = task?.title ?: "Task", onBack = component::onCloseClicked)
        if (state.isHydrating) {
            LoadingStrip(label = "Loading details…")
        }
        when {
            task == null && !state.isHydrating -> EmptyState(
                title = "Task not found",
                body = "This task may have been removed. Pick another from the tree.",
            )
            task == null -> Unit // brief hydrating gap before the row is observed; the bar above shows it
            else -> TaskDetailBody(
                task = task,
                isHydrating = state.isHydrating,
                onAddToPlan = component::onAddToPlanClicked,
            )
        }
    }
}

@Composable
private fun TaskDetailBody(
    task: Task,
    isHydrating: Boolean,
    onAddToPlan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        task.ref?.let { ref ->
            Text(
                text = ref,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = plexMono(),
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }
        WorkingStateBadge(task.workingState)

        val description = task.description
        when {
            !description.isNullOrBlank() -> Text(description, style = MaterialTheme.typography.bodyLarge)
            !isHydrating -> Text(
                text = "No description yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }

        Button(onClick = onAddToPlan, modifier = Modifier.fillMaxWidth()) {
            Text("Add to today's plan")
        }
    }
}
