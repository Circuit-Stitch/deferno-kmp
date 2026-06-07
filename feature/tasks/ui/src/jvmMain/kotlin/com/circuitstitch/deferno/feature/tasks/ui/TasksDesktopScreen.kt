package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskListComponent
import com.circuitstitch.deferno.feature.tasks.TaskPane
import com.circuitstitch.deferno.feature.tasks.TaskTreeComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent

/**
 * The Tasks screen, desktop edition. Where the Android screen is **single-pane** (it renders only
 * [TasksComponent.activePane]), the desktop screen leans into the large screen: when there is room it
 * shows a **two-pane list + detail/tree** layout — the ADR-0007 tier-2 "1 or 2 panes by size class"
 * vision, the desktop's best self rather than the phone layout stretched.
 *
 * It is adaptive off the continuous available width (ADR-0008 G1 — never a device-type check): at
 * [TasksTwoPaneMinWidth]+ the list stays pinned on the left while the most-recently-foregrounded
 * co-resident slot fills the right; narrower, it collapses to the same single-pane behaviour as
 * Android. The component owns all state ([detail]/[tree] are co-resident slots, [activePane] their
 * recency), so resizing across the breakpoint never drops what's open (G5). It reuses the slice's
 * shared commonMain atoms ([TaskRow], [PaneHeader], [WorkingStateBadge], [LoadingStrip], [EmptyState]).
 */
@Composable
fun TasksDesktopScreen(component: TasksComponent, modifier: Modifier = Modifier) {
    val detailSlot by component.detail.subscribeAsState()
    val treeSlot by component.tree.subscribeAsState()
    val activePane by component.activePane.subscribeAsState()
    val detail = detailSlot.child?.instance
    val tree = treeSlot.child?.instance

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= TasksTwoPaneMinWidth) {
            // Two panes: the list is always present on the left; the right pane is the active slot.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(TasksListPaneWidth).fillMaxHeight()) {
                    TaskListPane(component.list)
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    SecondaryPane(activePane, detail, tree)
                }
            }
        } else {
            // One pane: render the most-recently-foregrounded slot, falling back to the list — the
            // same precedence as the Android single-pane host (TasksScreen.kt).
            when {
                activePane == TaskPane.Tree && tree != null -> TaskTreePane(tree)
                activePane == TaskPane.Detail && detail != null -> TaskDetailPane(detail)
                tree != null -> TaskTreePane(tree)
                detail != null -> TaskDetailPane(detail)
                else -> TaskListPane(component.list)
            }
        }
    }
}

/** Below this available width the desktop Tasks screen collapses to a single pane (ADR-0007 tier-2). */
internal val TasksTwoPaneMinWidth = 720.dp

/** Fixed width of the list pane in the two-pane layout; the secondary pane takes the rest. */
private val TasksListPaneWidth = 360.dp

/** The right-hand pane in the two-pane layout: the active co-resident slot, else a gentle placeholder. */
@Composable
private fun SecondaryPane(
    activePane: TaskPane,
    detail: TaskDetailComponent?,
    tree: TaskTreeComponent?,
) {
    when {
        activePane == TaskPane.Tree && tree != null -> TaskTreePane(tree)
        activePane == TaskPane.Detail && detail != null -> TaskDetailPane(detail)
        tree != null -> TaskTreePane(tree)
        detail != null -> TaskDetailPane(detail)
        else -> EmptyState(
            title = "Nothing open",
            body = "Pick a task on the left to see its details here.",
        )
    }
}

/** The list pane — a thin renderer of [TaskListComponent], reusing [TaskRow]. */
@Composable
private fun TaskListPane(component: TaskListComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(
            title = "Tasks",
            actions = {
                TextButton(onClick = component::onRefresh, enabled = !state.isRefreshing) { Text("Refresh") }
            },
        )
        if (state.isRefreshing) {
            LoadingStrip(label = "Refreshing…")
        }
        if (state.tasks.isEmpty() && !state.isRefreshing) {
            EmptyState(
                title = "No tasks yet",
                body = "When you add a task, it shows up here. One small step at a time.",
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.tasks, key = { it.id.value }) { task ->
                    TaskRow(task = task, onClick = { component.onTaskClicked(task.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** The detail pane — a thin renderer of [TaskDetailComponent]; the component hydrates on creation (#22). */
@Composable
private fun TaskDetailPane(component: TaskDetailComponent, modifier: Modifier = Modifier) {
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
                body = "This task may have been removed. Pick another from the list.",
            )
            task == null -> Unit // brief hydrating gap before the row is observed; the bar above shows it
            else -> TaskDetailBody(
                task = task,
                isHydrating = state.isHydrating,
                onShowTree = component::onShowTreeClicked,
                onAddToPlan = component::onAddToPlanClicked,
            )
        }
    }
}

@Composable
private fun TaskDetailBody(
    task: Task,
    isHydrating: Boolean,
    onShowTree: () -> Unit,
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

        if (task.children.isNotEmpty()) {
            OutlinedButton(onClick = onShowTree, modifier = Modifier.fillMaxWidth()) {
                val count = task.children.size
                Text(if (count == 1) "Show its 1 step" else "Show its $count steps")
            }
        }
    }
}

/** The breakdown (tree) pane — a thin renderer of [TaskTreeComponent], one level at a time. */
@Composable
private fun TaskTreePane(component: TaskTreeComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(
            title = state.root?.title?.let { "Steps in “$it”" } ?: "Steps",
            onBack = component::onCloseClicked,
        )
        if (state.children.isEmpty()) {
            EmptyState(
                title = "No smaller steps yet",
                body = "Break this into next steps whenever you're ready — there's no rush.",
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.children, key = { it.id.value }) { child ->
                    TaskRow(task = child, onClick = { component.onChildClicked(child.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
