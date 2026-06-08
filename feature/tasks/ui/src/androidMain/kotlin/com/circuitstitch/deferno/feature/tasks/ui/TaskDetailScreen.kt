package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
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
        onSetWorkingState = component::onSetWorkingState,
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
    onSetWorkingState: (WorkingState) -> Unit,
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
                onSetWorkingState = onSetWorkingState,
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
    onSetWorkingState: (WorkingState) -> Unit,
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
        WorkingStateEditor(current = task.workingState, onSetWorkingState = onSetWorkingState)

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

/**
 * The interactive working-state control on the Tasks detail (#73): a selectable chip per
 * [WorkingState] with the [current] one selected, so the user can move the Task across all five states.
 * Tapping a chip forwards [onSetWorkingState]; the component issues the one lifecycle Command that
 * reaches that state and the change applies optimistically + offline-first (ADR-0001/0007). Re-tapping
 * the current state is a clean no-op (the executor's pre-flight gate rejects it before any write).
 *
 * Plain labels, no jargon, large touch targets, and a self-describing TalkBack semantic per chip
 * (design-principles.md); colour is reinforcement, never the sole signal.
 */
@Composable
internal fun WorkingStateEditor(
    current: WorkingState,
    onSetWorkingState: (WorkingState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Working state",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkingState.entries.forEach { state ->
                val label = workingStateLabel(state)
                val selected = state == current
                FilterChip(
                    selected = selected,
                    onClick = { onSetWorkingState(state) },
                    label = { Text(label) },
                    modifier = Modifier.semantics {
                        contentDescription = if (selected) "$label, current working state" else "Set to $label"
                    },
                )
            }
        }
    }
}

/** The plain, non-shaming label for each [WorkingState] (matches [WorkingStateBadge]). */
internal fun workingStateLabel(state: WorkingState): String = when (state) {
    WorkingState.Open -> "Open"
    WorkingState.InProgress -> "In progress"
    WorkingState.InReview -> "In review"
    WorkingState.Done -> "Done"
    WorkingState.Dropped -> "Set aside"
}
