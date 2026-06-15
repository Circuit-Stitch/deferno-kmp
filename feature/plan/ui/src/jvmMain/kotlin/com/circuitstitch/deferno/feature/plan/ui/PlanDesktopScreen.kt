package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.plan.PlanComponent

/**
 * The daily Plan screen, desktop edition — the app's calm home (design-principles.md: "open into
 * today's Plan, not the whole backlog"). A thin renderer of [PlanComponent]: it observes today's
 * ordered Tasks and forwards taps, holding no logic of its own (ADR-0007: Views are thin renderers of
 * shared state). Its "Today" title + Refresh now live in the shell's single top bar (Cand 1).
 *
 * Desktop divergence from the Android screen (ADR-0007: not the phone layout stretched): the list is
 * held to a comfortable **reading width** and centred rather than spanning a wide window edge-to-edge.
 * It reuses the shared commonMain atoms ([PlanTaskRow], [EmptyPlan], [LoadingStrip]).
 */
@Composable
fun PlanDesktopScreen(component: PlanComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    PlanDesktopContent(
        tasks = state.tasks,
        isRefreshing = state.isRefreshing,
        onTaskClick = component::onTaskClicked,
        modifier = modifier,
    )
}

/** Comfortable reading column width for the Plan on a wide desktop window. */
private val PlanReadingWidth = 760.dp

/** Stateless body — easy to render in a preview / test with fixed inputs. */
@Composable
internal fun PlanDesktopContent(
    tasks: List<Task>,
    isRefreshing: Boolean,
    onTaskClick: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxSize().widthIn(max = PlanReadingWidth)) {
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
}
