package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Plan's ✦ suggestion (#375) — the pure precedence helper plus the "why" line the choice cards read.
 *
 * These run on the JVM-fast path and exercise the **shared** dashboard body, so they cover the desktop and
 * Android Plan alike (both render [PlanContent] / [WhatsNextContent], ADR-0004 #27).
 *
 * The load-bearing guarantee alongside them: priority moves only the *suggestion*, never the Plan's own
 * order. The day list is what the person arranged, and [planList_keepsTheCuratedOrder_whenAFireTaskIsPicked]
 * pins that a Fire task lower down is suggested **without** being hoisted up the list.
 */
@OptIn(ExperimentalTestApi::class)
class PlanSuggestionTest {

    private val today = LocalDate(2026, 6, 15)

    private fun task(
        id: String,
        title: String,
        priority: Priority = Priority.Normal,
        pinned: Boolean = false,
        completeBy: Instant? = null,
    ) = Task(
        id = TaskId(id),
        orgSlug = "u-deferno",
        title = title,
        workingState = WorkingState.Open,
        priority = priority,
        pinned = pinned,
        completeBy = completeBy,
        dateCreated = Instant.parse("2026-06-01T09:00:00Z"),
    )

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        DefernoTheme(palette = DefernoPalette.Deferno) {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    // --- the pick ---

    @Test
    fun suggested_prefersAFireTaskOverAPinnedOne() {
        // #375: Fire outranks pinned. The person marking something urgent is the stronger "start here"
        // signal than having parked it at the top of the plan.
        val picked = listOf(
            task("1", "Water the plants", pinned = true),
            task("2", "Call the plumber", priority = Priority.Fire),
        ).suggested()

        assertEquals(TaskId("2"), picked?.id)
    }

    @Test
    fun suggested_fallsBackToPinned_thenToTheFirstInThePlan() {
        // With no Fire task the pre-existing precedence is untouched: pinned first, else whatever the
        // person put at the top.
        assertEquals(
            TaskId("2"),
            listOf(task("1", "Water the plants"), task("2", "Call the plumber", pinned = true)).suggested()?.id,
        )
        assertEquals(
            TaskId("1"),
            listOf(task("1", "Water the plants"), task("2", "Call the plumber")).suggested()?.id,
        )
        assertNull(emptyList<Task>().suggested())
    }

    @Test
    fun suggested_picksTheFirstFireTask_whenThereAreSeveral() {
        // Within the Fire bucket the person's own order still decides — we don't re-rank inside it.
        val picked = listOf(
            task("1", "Water the plants"),
            task("2", "Call the plumber", priority = Priority.Fire),
            task("3", "File the taxes", priority = Priority.Fire),
        ).suggested()

        assertEquals(TaskId("2"), picked?.id)
    }

    @Test
    fun suggested_ignoresBacklog_whichSinksButStaysVisible() {
        // Backlog is a ranking bucket, not a filter: a Backlog task is never suggested over its peers,
        // but it is still in the plan and still the fallback when it is all there is.
        assertEquals(
            TaskId("2"),
            listOf(
                task("1", "Someday idea", priority = Priority.Backlog),
                task("2", "Call the plumber", priority = Priority.Fire),
            ).suggested()?.id,
        )
        assertEquals(
            TaskId("1"),
            listOf(task("1", "Someday idea", priority = Priority.Backlog)).suggested()?.id,
        )
    }

    // --- the curated order ---

    @Test
    fun planList_keepsTheCuratedOrder_whenAFireTaskIsPicked() = runComposeUiTest {
        // The Plan's order is the person's own arrangement and must not move. With a plain task first and
        // a Fire task second, the ✦ banner names the Fire one — while the day list below still runs
        // plain-then-Fire. Read positionally: the Fire title appears twice (banner + its row), and the
        // plain task's single row sits BETWEEN them.
        setContent {
            Themed {
                PlanContent(
                    tasks = listOf(
                        task("1", "Water the plants"),
                        task("2", "Call the plumber", priority = Priority.Fire),
                    ),
                    isRefreshing = false,
                    onTaskClick = {},
                    today = today,
                    onStartFocus = {},
                    onWhatsNext = {},
                )
            }
        }

        val fire = onAllNodesWithText("Call the plumber").fetchSemanticsNodes()
            .map { it.positionInRoot.y }
            .sorted()
        val plain = onAllNodesWithText("Water the plants").fetchSemanticsNodes()
            .map { it.positionInRoot.y }

        assertEquals(2, fire.size, "the Fire task should appear in the suggestion banner AND its own day row")
        assertEquals(1, plain.size)
        assertTrue(fire.first() < plain.single(), "the ✦ banner (the Fire task) sits above the day list")
        assertTrue(plain.single() < fire.last(), "the day list keeps the curated order — plain task still first")
    }

    // --- the why line ---

    @Test
    fun whyLine_readsTheFireWording_forAnUndatedFireTask() = runComposeUiTest {
        setContent {
            Themed {
                WhatsNextContent(
                    tasks = listOf(task("1", "Call the plumber", priority = Priority.Fire)),
                    onBack = {},
                    onStartFocus = {},
                )
            }
        }

        onNodeWithText("You said this one's urgent").assertExists()
    }

    @Test
    fun whyLine_prefersFireOverPinned() = runComposeUiTest {
        // The Fire arm sits ABOVE the pinned arm: a task that is both reads as urgent, not as pinned.
        setContent {
            Themed {
                WhatsNextContent(
                    tasks = listOf(task("1", "Call the plumber", priority = Priority.Fire, pinned = true)),
                    onBack = {},
                    onStartFocus = {},
                )
            }
        }

        onNodeWithText("You said this one's urgent").assertExists()
        onNodeWithText("You said this one matters").assertDoesNotExist()
    }

    @Test
    fun whyLine_leavesThePinnedAndQuickWinArmsIntact() = runComposeUiTest {
        setContent {
            Themed {
                WhatsNextContent(
                    tasks = listOf(
                        task("1", "Water the plants", pinned = true),
                        task("2", "Reply to Sam"),
                    ),
                    onBack = {},
                    onStartFocus = {},
                )
            }
        }

        onNodeWithText("You said this one matters").assertExists()
        onNodeWithText("A quick win, if you want momentum").assertExists()
    }
}
