package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent

/**
 * The Task detail pane (#27). Thin renderer of [TaskDetailComponent]: observes the hydrating row and
 * forwards the close / show-breakdown / add-to-plan intents. The component hydrates on creation
 * (summary → full, #22); this View just reflects [TaskDetailComponent.state].
 */
@Composable
fun TaskDetailScreen(component: TaskDetailComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    TaskDetailContent(
        task = state.task,
        isHydrating = state.isHydrating,
        onClose = component::onCloseClicked,
        onShowTree = component::onShowTreeClicked,
        onAddToPlan = component::onAddToPlanClicked,
        modifier = modifier,
    )
}

/** Stateless body — rendered directly by screenshot/UI tests with fixed inputs. */
@Composable
internal fun TaskDetailContent(
    task: Task?,
    isHydrating: Boolean,
    onClose: () -> Unit,
    onShowTree: () -> Unit,
    onAddToPlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // In single-pane the leading control returns to the list, so it reads as "Back".
        PaneHeader(title = task?.title ?: "Task", onBack = onClose)
        if (isHydrating) {
            LoadingStrip(label = "Loading details…")
        }
        when {
            task == null && !isHydrating -> EmptyState(
                title = "Task not found",
                body = "This task may have been removed. Head back to your list.",
            )
            task == null -> Unit // brief hydrating gap before the row is observed; the bar above shows it
            else -> TaskBody(
                task = task,
                isHydrating = isHydrating,
                onShowTree = onShowTree,
                onAddToPlan = onAddToPlan,
            )
        }
    }
}

@Composable
private fun TaskBody(
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

        Button(
            onClick = onAddToPlan,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        ) { Text("Add to today's plan") }

        if (task.children.isNotEmpty()) {
            OutlinedButton(
                onClick = onShowTree,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) {
                val count = task.children.size
                Text(if (count == 1) "Show its 1 step" else "Show its $count steps")
            }
        }
    }
}
