package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.tasks.TaskListComponent

/**
 * The Task list pane (#27). A thin renderer of [TaskListComponent]: it observes the component's
 * [TaskListComponent.state] and forwards taps/refresh to it, holding no logic of its own (ADR-0007:
 * Views are thin renderers of shared state).
 */
@Composable
fun TaskListScreen(component: TaskListComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    TaskListContent(
        tasks = state.tasks,
        isRefreshing = state.isRefreshing,
        onTaskClick = component::onTaskClicked,
        onRefresh = component::onRefresh,
        modifier = modifier,
    )
}

/** Stateless body — rendered directly by screenshot/UI tests with fixed inputs. */
@Composable
internal fun TaskListContent(
    tasks: List<Task>,
    isRefreshing: Boolean,
    onTaskClick: (TaskId) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(
            title = "Tasks",
            actions = {
                TextButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text("Refresh") }
            },
        )
        if (isRefreshing) {
            LoadingStrip(label = "Refreshing…")
        }
        if (tasks.isEmpty() && !isRefreshing) {
            EmptyState(
                title = "No tasks yet",
                body = "When you add a task, it shows up here. One small step at a time.",
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(tasks, key = { it.id.value }) { task ->
                    TaskRow(task = task, onClick = { onTaskClick(task.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
