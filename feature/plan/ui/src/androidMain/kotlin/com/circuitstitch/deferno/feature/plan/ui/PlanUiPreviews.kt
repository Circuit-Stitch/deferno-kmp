package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.time.Instant

// IDE-preview-only sample data + `@Preview`s for the shared Plan atoms (PlanUi.kt). They live in
// androidMain — the androidx `@Preview` annotation resolves on the Android target, and Android Studio
// renders previews from Android source sets. The atoms are `internal` in commonMain, visible here.

/** A small, calm sample plan for the preview pane (mirrors the demo fixture). Shared with the screen. */
internal fun previewSamplePlanTasks(): List<Task> = listOf(
    previewSamplePlanTask("t-1", "Plan the spring launch", WorkingState.InProgress, "u-deferno-1"),
    previewSamplePlanTask("t-2", "Water the plants", WorkingState.Open, "u-deferno-2"),
    previewSamplePlanTask("t-3", "Reply to Sam", WorkingState.Done, "u-deferno-3"),
)

private fun previewSamplePlanTask(id: String, title: String, workingState: WorkingState, ref: String): Task =
    Task(
        id = TaskId(id),
        orgSlug = "u-deferno",
        title = title,
        workingState = workingState,
        ref = ref,
        dateCreated = Instant.parse("2026-06-01T09:00:00Z"),
    )

@Preview
@Composable
private fun PlanTaskRowPreview() {
    DefernoTheme {
        PlanTaskRow(task = previewSamplePlanTasks().first(), onClick = {})
    }
}

@Preview
@Composable
private fun EmptyPlanPreview() {
    DefernoTheme { EmptyPlan() }
}

@Preview
@Composable
private fun LoadingStripPreview() {
    DefernoTheme { LoadingStrip(label = "Refreshing your plan…") }
}
