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
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanRow
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
        workingState: WorkingState = WorkingState.Open,
    ) = Task(
        id = TaskId(id),
        orgSlug = "u-deferno",
        title = title,
        workingState = workingState,
        priority = priority,
        pinned = pinned,
        completeBy = completeBy,
        dateCreated = Instant.parse("2026-06-01T09:00:00Z"),
    )

    /** A Task plan row — `task` populated, which is what every Task-shaped affordance keys on (#385). */
    private fun taskRow(
        id: String,
        title: String,
        priority: Priority = Priority.Normal,
        pinned: Boolean = false,
    ) = task(id, title, priority, pinned).let {
        PlanRow(item = Item(id = it.id.value, kind = ItemKind.Task, title = it.title), task = it)
    }

    /** A recurring plan row — no `task`, no deadline, no working state. */
    private fun recurringRow(id: String, title: String, kind: ItemKind = ItemKind.Habit) =
        PlanRow(item = Item(id = id, kind = kind, title = title))

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
        ).suggestedTask()

        assertEquals(TaskId("2"), picked?.id)
    }

    @Test
    fun suggested_fallsBackToPinned_thenToTheFirstInThePlan() {
        // With no Fire task the pre-existing precedence is untouched: pinned first, else whatever the
        // person put at the top.
        assertEquals(
            TaskId("2"),
            listOf(task("1", "Water the plants"), task("2", "Call the plumber", pinned = true)).suggestedTask()?.id,
        )
        assertEquals(
            TaskId("1"),
            listOf(task("1", "Water the plants"), task("2", "Call the plumber")).suggestedTask()?.id,
        )
        assertNull(emptyList<Task>().suggestedTask())
    }

    @Test
    fun suggested_picksTheFirstFireTask_whenThereAreSeveral() {
        // Within the Fire bucket the person's own order still decides — we don't re-rank inside it.
        val picked = listOf(
            task("1", "Water the plants"),
            task("2", "Call the plumber", priority = Priority.Fire),
            task("3", "File the taxes", priority = Priority.Fire),
        ).suggestedTask()

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
            ).suggestedTask()?.id,
        )
        assertEquals(
            TaskId("1"),
            listOf(task("1", "Someday idea", priority = Priority.Backlog)).suggestedTask()?.id,
        )
    }

    // --- the pick, and finished work (#375 review) ---

    /**
     * A finished task is never what to start next. It keeps whatever priority bucket it had, and the Plan
     * does render terminal rows (`observeActive()` filters tombstones, not states), so an unguarded Fire
     * arm answers "start here" with something already done.
     *
     * This is the **Compose half of the #375 review**, which landed on both Apple views and never here —
     * `PlanView.swift`'s `suggested(_:)` has gated the Fire lane on open work since then, so until this
     * fix Android and desktop picked a different row than iPhone and Mac for the same day.
     */
    @Test
    fun suggested_neverStartsWithAFinishedFireTask() {
        val picked = listOf(
            task("1", "Call the plumber", priority = Priority.Fire, workingState = WorkingState.Done),
            task("2", "Water the plants"),
        ).suggestedTask()

        assertEquals(TaskId("2"), picked?.id, "the open task, not the finished Fire one")
    }

    @Test
    fun suggested_skipsFinishedWorkAtTheFallbackTierToo() {
        // Not just the Fire lane: the plain "first in the plan" arm is guarded as well, so a day whose
        // first row is done still suggests the first row you could actually pick up.
        assertEquals(
            TaskId("2"),
            listOf(
                task("1", "Yesterday's leftovers", workingState = WorkingState.Dropped),
                task("2", "Water the plants"),
            ).suggestedTask()?.id,
        )
    }

    @Test
    fun suggested_stillReturnsARowWhenEverythingIsFinished() {
        // The final arm is deliberately unguarded (mirroring `PlanView.swift`): a fully-finished day keeps
        // its banner rather than silently losing it. Losing the ✦ on the one day you cleared the plan
        // would read as a bug, not as praise.
        assertEquals(
            TaskId("1"),
            listOf(
                task("1", "Water the plants", workingState = WorkingState.Done),
                task("2", "Call the plumber", workingState = WorkingState.Done),
            ).suggestedTask()?.id,
        )
    }

    /**
     * The `pinned` arm is deliberately NOT terminal-guarded, because `PlanView.swift`'s isn't either.
     * Pinning this down stops a well-meaning "fix" to one platform from re-opening the divergence the
     * test above closes — if this behaviour is ever changed, it must change on all four platforms at once.
     */
    @Test
    fun suggested_keepsApplesUnguardedPinnedArm_soTheFourPlatformsStayIdentical() {
        assertEquals(
            TaskId("1"),
            listOf(
                task("1", "Water the plants", pinned = true, workingState = WorkingState.Done),
                task("2", "Call the plumber"),
            ).suggestedTask()?.id,
        )
    }

    // --- the pick, across a cross-kind day (#385) ---

    /**
     * The ✦ is Task-only. Its verb is "Start", and starting is what you do to a Task — a Habit is a
     * commitment you keep, not work you pick up, and tapping through leads to Focus mode, which is
     * Task-shaped end to end. The plan became cross-kind in #385, so the row-level helper delegates to
     * the same one precedence rule the What's-next screen uses and the two cannot drift.
     */
    @Test
    fun suggested_picksTheTaskRowUsingTheSamePrecedence() {
        val picked = listOf(
            recurringRow("h1", "Take a Walk"),
            taskRow("1", "Water the plants", pinned = true),
            taskRow("2", "Call the plumber", priority = Priority.Fire),
        ).suggested()

        assertEquals("2", picked?.item?.id)
        assertEquals(TaskId("2"), picked?.task?.id, "the picked row still carries its concrete Task")
    }

    @Test
    fun suggested_isNullWhenTheDayHoldsNothingStartable() {
        // A plan of nothing but recurring rows gets no ✦ — the honest outcome rather than a suggestion
        // whose "Start" button leads to a Focus mode that cannot render it.
        assertNull(
            listOf(
                recurringRow("h1", "Take a Walk"),
                recurringRow("c1", "Take shot", ItemKind.Chore),
            ).suggested(),
        )
        assertNull(emptyList<PlanRow>().suggested())
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
                    rows = listOf(
                        taskRow("1", "Water the plants"),
                        taskRow("2", "Call the plumber", priority = Priority.Fire),
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

    /**
     * "Already done" outranks every reason-to-start, including the deadline arm above it (#375 review).
     * The string was in all five Compose locale files with no code reading it while both Apple views
     * rendered it — `L10nCatalogParityTest` compares the catalogs to each other and never to a call site,
     * so a translated-but-orphaned key looks identical to a used one.
     */
    @Test
    fun whyLine_readsAlreadyDone_forAFinishedTask_evenWhenItIsFireAndDated() = runComposeUiTest {
        setContent {
            Themed {
                WhatsNextContent(
                    tasks = listOf(
                        task(
                            "1",
                            "Call the plumber",
                            priority = Priority.Fire,
                            completeBy = Instant.parse("2026-06-20T17:00:00Z"),
                            workingState = WorkingState.Done,
                        ),
                    ),
                    onBack = {},
                    onStartFocus = {},
                )
            }
        }

        onNodeWithText("Already wrapped up — pick another?").assertExists()
        onNodeWithText("You said this one's urgent").assertDoesNotExist()
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

    /**
     * Regression (#375 review): the "What's next" screen renders only the first three plan entries, so the
     * suggestion must be chosen from those three. Picking across the whole plan let a Fire task below the
     * fold win, and `selected` — resolved against the rendered choices — then came back null: no card
     * selected, no ✦ chip, dead primary button.
     */
    @Test
    fun suggestionIsChosenFromTheRenderedChoicesNotTheWholePlan() {
        val plan = listOf(
            task("a", "first"),
            task("b", "second"),
            task("c", "third"),
            task("far-fire", "below the fold", priority = Priority.Fire),
        )
        val choices = plan.take(3)

        // Across the whole plan the Fire task wins — but it is not rendered...
        assertEquals("far-fire", plan.suggestedTask()?.id?.value)
        // ...so the screen must pick inside what it draws, and always resolve to a rendered card.
        val suggested = choices.suggestedTask()
        assertEquals("a", suggested?.id?.value)
        assertTrue(choices.any { it.id == suggested?.id }, "the suggestion must be one of the rendered cards")
    }
}
