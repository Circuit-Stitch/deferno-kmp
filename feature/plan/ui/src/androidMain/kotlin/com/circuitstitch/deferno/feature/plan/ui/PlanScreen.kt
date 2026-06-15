package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.plan.PlanComponent

/**
 * The daily Plan pane (#27) — the app's calm home (design-principles.md: "open into today's Plan,
 * not the whole backlog"). A thin renderer of [PlanComponent]: observes today's ordered Tasks and
 * forwards taps (open the Task), holding no logic of its own. Its "Today" title + Refresh now live in
 * the shell's single top bar (Cand 1), so this pane is just the list. The Android-native screen; its
 * reusable atoms live in commonMain (PlanUi.kt) for the desktop View to share.
 */
@Composable
fun PlanScreen(component: PlanComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    PlanContent(
        tasks = state.tasks,
        isRefreshing = state.isRefreshing,
        onTaskClick = component::onTaskClicked,
        modifier = modifier,
    )
}

/** Stateless body — rendered directly by screenshot/UI tests with fixed inputs. */
@Composable
internal fun PlanContent(
    tasks: List<Task>,
    isRefreshing: Boolean,
    onTaskClick: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (isRefreshing) {
            LoadingStrip(label = "Refreshing your plan…")
        }
        if (tasks.isEmpty() && !isRefreshing) {
            EmptyPlan()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(tasks, key = { it.id.value }) { task ->
                    PlanTaskRow(task = task, onClick = { onTaskClick(task.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
