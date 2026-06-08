package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.time.Instant

// IDE-preview-only sample data + `@Preview`s for the shared Tasks atoms (TaskUi.kt). They live in
// androidMain — the androidx `@Preview` annotation resolves on the Android target, and Android Studio
// renders previews from Android source sets. The atoms are `internal` in commonMain, visible here.

/** A single full sample Task for the preview pane (mirrors the demo fixture). Shared with the screens. */
internal fun previewSampleTask(
    id: String = "t-1",
    title: String = "Plan the spring launch",
    workingState: WorkingState = WorkingState.InProgress,
    ref: String? = "u-deferno-1",
    pinned: Boolean = false,
): Task = Task(
    id = TaskId(id),
    orgSlug = "u-deferno",
    title = title,
    workingState = workingState,
    ref = ref,
    pinned = pinned,
    dateCreated = Instant.parse("2026-06-01T09:00:00Z"),
)

/** A small, calm sample list for the list/tree screen previews — one Task per working state. */
internal fun previewSampleTasks(): List<Task> = listOf(
    previewSampleTask("t-1", "Plan the spring launch", WorkingState.InProgress, pinned = true),
    previewSampleTask("t-2", "Water the plants", WorkingState.Open, ref = "u-deferno-2"),
    previewSampleTask("t-3", "Reply to Sam", WorkingState.InReview, ref = "u-deferno-3"),
    previewSampleTask("t-4", "Schedule the post", WorkingState.Done, ref = "u-deferno-4"),
    previewSampleTask("t-5", "Old idea worth revisiting", WorkingState.Dropped, ref = "u-deferno-5"),
)

@Preview
@Composable
private fun TaskRowPreview() {
    DefernoTheme {
        TaskRow(task = previewSampleTask(pinned = true), onClick = {})
    }
}

@Preview
@Composable
private fun WorkingStateBadgePreview() {
    DefernoTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkingState.entries.forEach { WorkingStateBadge(it) }
        }
    }
}

@Preview
@Composable
private fun PaneHeaderPreview() {
    DefernoTheme {
        PaneHeader(title = "Steps in “Plan the spring launch”", onBack = {})
    }
}

@Preview
@Composable
private fun LoadingStripPreview() {
    DefernoTheme { LoadingStrip(label = "Refreshing…") }
}

@Preview
@Composable
private fun EmptyStatePreview() {
    DefernoTheme {
        EmptyState(
            title = "No tasks yet",
            body = "When you add a task, it shows up here. One small step at a time.",
        )
    }
}
