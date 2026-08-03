package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The desktop Task-detail properties test (#375, cf. #86) — a Compose-Multiplatform UI test on the
 * JVM-fast path (no device) over the same [TaskDetailContent] the desktop `TaskDetailScreen` renders.
 *
 * It covers the two new property rows the desktop gets alongside Android: the SOFT **target date** (a
 * date-only picker + a clear affordance, never presented as a deadline) and the **priority** bucket
 * (the STATUS row's picker-driven twin). The write seams themselves are unit-tested in
 * :feature:tasks (TaskDetailComponentTest) — this pins the View's forwarding + its a11y readings.
 */
@OptIn(ExperimentalTestApi::class)
class TaskDetailPropertiesTest {

    private fun task(
        targetDate: Instant? = null,
        completeBy: Instant? = null,
        priority: Priority = Priority.Normal,
    ) = Task(
        id = TaskId("1"),
        orgSlug = "u-deferno",
        title = "Plan the spring launch",
        workingState = WorkingState.Open,
        completeBy = completeBy,
        targetDate = targetDate,
        priority = priority,
        dateCreated = Instant.parse("2026-06-01T09:00:00Z"),
    )

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        DefernoTheme(palette = DefernoPalette.Deferno) {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    @Composable
    private fun Detail(
        task: Task,
        onSetTargetDate: (LocalDate?) -> Unit = {},
        onSetPriority: (Priority) -> Unit = {},
        onSetDeadline: (LocalDate?) -> Unit = {},
    ) {
        TaskDetailContent(
            state = TaskDetailState(task = task, isHydrating = false),
            onAddToPlan = {},
            onSetWorkingState = {},
            onSetDeadline = onSetDeadline,
            onSetTargetDate = onSetTargetDate,
            onSetPriority = onSetPriority,
            onSetLabels = {},
            onToggleSubtask = {},
            onOpenSubtask = {},
            onAddSubtask = {},
            onPostComment = {},
            onEditComment = { _, _ -> },
            onDeleteComment = {},
            onAddAttachment = {},
            onDeleteAttachment = {},
            onSetAttachmentCaption = { _, _ -> },
        )
    }

    @Test
    fun targetDateRow_isAlwaysPresent_andReadsItsOwnEmptyState() {
        // With no soft target the row still shows (it is the only way to set one) and reads the muted
        // "No target date" — never a "due"/deadline word, and never a bare em dash.
        runComposeUiTest {
            setContent { Themed { Detail(task()) } }

            onNodeWithContentDescription("Target date: No target date. Tap to change.")
                .performScrollTo()
                .assertExists()
        }
    }

    @Test
    fun targetDateRow_opensADateOnlyPicker() {
        // Date-granular by intent: tapping the value opens a plain date picker — there is no target
        // time-of-day, so the deadline's clock axis must not ride along.
        runComposeUiTest {
            setContent { Themed { Detail(task()) } }

            onNodeWithContentDescription("Target date: No target date. Tap to change.")
                .performScrollTo()
                .performClick()

            onNodeWithText("Set").assertExists()
            onNodeWithText("Cancel").assertExists()
        }
    }

    @Test
    fun targetDateClear_forwardsNull_withoutTouchingTheDeadline() {
        // The two dates are independent peers: clearing the soft target must not issue a deadline write.
        val targetDates = mutableListOf<LocalDate?>()
        val deadlines = mutableListOf<LocalDate?>()
        runComposeUiTest {
            setContent {
                Themed {
                    Detail(
                        task = task(
                            targetDate = Instant.parse("2026-06-20T23:59:59Z"),
                            completeBy = Instant.parse("2026-06-30T17:00:00Z"),
                        ),
                        onSetTargetDate = { targetDates += it },
                        onSetDeadline = { deadlines += it },
                    )
                }
            }

            onNodeWithContentDescription("Clear target date").performScrollTo().performClick()
        }

        assertEquals(listOf<LocalDate?>(null), targetDates)
        assertEquals(emptyList<LocalDate?>(), deadlines)
    }

    @Test
    fun priorityRow_opensThePickerAndForwardsTheChosenBucket() {
        val buckets = mutableListOf<Priority>()
        runComposeUiTest {
            setContent { Themed { Detail(task = task(), onSetPriority = { buckets += it }) } }

            onNodeWithContentDescription("Priority: Normal. Tap to change.").performScrollTo().performClick()
            onNodeWithText("Fire").performClick()
        }

        assertEquals(listOf(Priority.Fire), buckets)
    }

    @Test
    fun priorityRow_readsTheCurrentBucket() {
        // Backlog reads as its own plain bucket name — it sinks an item in ranked views but keeps it
        // visible, so the row never says hidden/archived/dropped.
        runComposeUiTest {
            setContent { Themed { Detail(task(priority = Priority.Backlog)) } }

            onNodeWithContentDescription("Priority: Backlog. Tap to change.").performScrollTo().assertExists()
        }
    }
}
