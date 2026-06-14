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
import com.circuitstitch.deferno.feature.tasks.TaskTreeComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent

/**
 * The Tasks screen, desktop edition — the desktop counterpart of the adaptive Android `TasksScreen`
 * (#29). Both render the component's co-resident slots as **1 or 2 panes by window size class** (the
 * ADR-0007 tier-2 "1 or 2 panes" vision, each platform's best self rather than the phone layout
 * stretched): Android via M3 `ListDetailPaneScaffold`, desktop via `BoxWithConstraints` here — when
 * there is room the list pins on the left and the most-recently-foregrounded co-resident slot fills
 * the right.
 *
 * It is adaptive off the continuous available width (ADR-0008 G1 — never a device-type check): at
 * [TasksTwoPaneMinWidth]+ it shows two panes; narrower, it collapses to a single pane — the same
 * compact fold as the Android screen. The component owns all state ([detail]/[tree] are co-resident
 * slots, [activePane] their recency), so resizing across the breakpoint never drops what's open (G5).
 * It reuses the slice's shared commonMain atoms ([TaskRow], [PaneHeader], [WorkingStateBadge],
 * [LoadingStrip], [EmptyState]).
 */
@Composable
fun TasksDesktopScreen(component: TasksComponent, modifier: Modifier = Modifier) {
    val detailSlot by component.detail.subscribeAsState()
    val treeSlot by component.tree.subscribeAsState()
    val activePane by component.activePane.subscribeAsState()
    val detail = detailSlot.child?.instance
    val tree = treeSlot.child?.instance

    // The shared secondary-pane precedence (#67), identical to the Android screen's (TasksScreen.kt):
    // which co-resident slot fills the secondary region. Only the None fallback differs by layout — an
    // empty "pick a task" state beside the two-pane list, the list itself in the single-pane fold.
    val slot = resolveSecondarySlot(activePane, hasDetail = detail != null, hasTree = tree != null)

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= TasksTwoPaneMinWidth) {
            // Two panes: the list is always present on the left; the right pane is the resolved slot.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(TasksListPaneWidth).fillMaxHeight()) {
                    TaskListPane(component.list)
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    SecondaryPane(slot, detail, tree)
                }
            }
        } else {
            // One pane: render the resolved slot, falling back to the list (not the empty state) — the
            // home state when nothing is open.
            when (slot) {
                SecondarySlot.Tree -> tree?.let { TaskTreePane(it) }
                SecondarySlot.Detail -> detail?.let { TaskDetailScreen(it) }
                SecondarySlot.None -> TaskListPane(component.list)
            }
        }
    }
}

/** Below this available width the desktop Tasks screen collapses to a single pane (ADR-0007 tier-2). */
internal val TasksTwoPaneMinWidth = 720.dp

/** Fixed width of the list pane in the two-pane layout; the secondary pane takes the rest. */
private val TasksListPaneWidth = 360.dp

/** The right-hand pane in the two-pane layout: the resolved co-resident slot, else a gentle placeholder. */
@Composable
private fun SecondaryPane(
    slot: SecondarySlot,
    detail: TaskDetailComponent?,
    tree: TaskTreeComponent?,
) {
    when (slot) {
        // The helper only returns Tree/Detail when that slot is open, so the instance is non-null here.
        SecondarySlot.Tree -> tree?.let { TaskTreePane(it) }
        SecondarySlot.Detail -> detail?.let { TaskDetailScreen(it) }
        SecondarySlot.None -> EmptyState(
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
