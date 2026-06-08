package com.circuitstitch.deferno.feature.tasks.ui

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
import androidx.compose.ui.tooling.preview.Preview
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.tasks.TaskTreeComponent

/**
 * The Task breakdown (tree) pane (#27). Thin renderer of [TaskTreeComponent]: shows the root's
 * direct children one level at a time — "decompose to defeat paralysis" (design-principles.md) —
 * forwarding a child tap (drill in) and close to the component.
 */
@Composable
fun TaskTreeScreen(component: TaskTreeComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    TaskTreeContent(
        rootTitle = state.root?.title,
        children = state.children,
        onChildClick = component::onChildClicked,
        onClose = component::onCloseClicked,
        modifier = modifier,
    )
}

/** Stateless body — rendered directly by screenshot/UI tests with fixed inputs. */
@Composable
internal fun TaskTreeContent(
    rootTitle: String?,
    children: List<Task>,
    onChildClick: (TaskId) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(
            title = rootTitle?.let { "Steps in “$it”" } ?: "Steps",
            onBack = onClose,
        )
        if (children.isEmpty()) {
            EmptyState(
                title = "No smaller steps yet",
                body = "Break this into next steps whenever you're ready — there's no rush.",
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(children, key = { it.id.value }) { child ->
                    TaskRow(task = child, onClick = { onChildClick(child.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

// --- @Preview ---

@Preview
@Composable
private fun TaskTreeContentPreview() {
    DefernoTheme {
        TaskTreeContent(
            rootTitle = "Plan the spring launch",
            children = previewSampleTasks().take(3),
            onChildClick = {},
            onClose = {},
        )
    }
}

@Preview
@Composable
private fun TaskTreeContentEmptyPreview() {
    DefernoTheme {
        TaskTreeContent(rootTitle = "Water the plants", children = emptyList(), onChildClick = {}, onClose = {})
    }
}
