package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.circuitstitch.deferno.feature.plan.suggestedTask
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the Plan's ✦ suggestion (#375) looks like **once rendered** — the day list around the banner, which
 * row wears the highlight, the "why" line on the choice cards, and which tasks the What's-next screen is
 * even allowed to suggest from.
 *
 * The precedence rule itself is not here: it is `:feature:plan`'s [suggestedTask], the one copy all four
 * platforms read, tested in that module's `commonTest` — where the tests compile against the Apple targets
 * that read the same function, and run on the Android host as well as this JVM path (in CI, only those two:
 * `ci.yml` is Linux, so the Apple test tasks self-disable — ADR-0006, #368). What is left here is what only
 * a composition can answer, so every test below goes through `setContent` — one that computes an
 * expectation in its own body and asserts against that is guarding nothing about the screen.
 *
 * They run on the JVM-fast path and exercise the **shared** dashboard body, so they cover the desktop and
 * Android Plan alike: Android's `MainShell` renders `PlanScreen` and desktop's renders
 * `PlanDesktopScreen`, which is that same `PlanScreen` centred at a reading width — and both funnel into
 * [PlanContent] / [WhatsNextContent] (ADR-0004 #27).
 *
 * The load-bearing guarantee: priority moves only the *suggestion*, never the Plan's own order. The day
 * list is what the person arranged, and [planList_keepsTheCuratedOrder_whenAFireTaskIsPicked] pins that a
 * Fire task lower down is suggested **without** being hoisted up the list.
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

    /** A recurring plan row — no `task`, so nothing Task-shaped applies to it and it is never the ✦. */
    private fun recurringRow(id: String, title: String, kind: ItemKind = ItemKind.Habit) =
        PlanRow(item = Item(id = id, kind = kind, title = title))

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        DefernoTheme(palette = DefernoPalette.Deferno) {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    /**
     * How far a day row's title starts to the right of its own subline, in pixels — the readable signature
     * of the ✦ highlight in the layout. `DayRow` draws the sparkle icon and its spacer *before* the title
     * inside the title/subline column, so a highlighted row's title is inset from the subline directly
     * beneath it while a flat row's sits flush with it. Zero means "this row drew no ✦".
     *
     * Read off the **unmerged** tree deliberately: a Task row is `clickable`, and a clickable node absorbs
     * its descendants' semantics into one row node — on the merged tree the title and the subline would
     * resolve to that same node and every gutter would read as zero, passing whether or not the marker is
     * there. Where a title appears twice (the banner repeats the suggested task's title above the list)
     * the lowest node on screen is the day row's.
     */
    private fun ComposeUiTest.titleGutter(title: String, subline: String): Float {
        val titleX = onAllNodesWithText(title, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .maxBy { it.positionInRoot.y }
            .positionInRoot.x
        val sublineX = onNodeWithText(subline, useUnmergedTree = true)
            .fetchSemanticsNode()
            .positionInRoot.x
        return titleX - sublineX
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

    // --- the day list's highlight ---

    /**
     * The control for [dayList_highlightsNoRow_whenTheDayHoldsNothingStartable]: on a day that *does* have
     * a suggestion, exactly the suggested row wears the ✦ and the recurring row beside it does not.
     *
     * It exists so that the gutter measurement cannot quietly stop meaning anything. [titleGutter] reads
     * the highlight off the *layout*, because neither half of the highlight can be asserted on by name:
     * the ✦ is an `Icon` with a null `contentDescription`, and the `HorizontalDivider` it suppresses
     * carries no semantics at all. A positive case pins that signature, so if the marker ever moves or
     * changes shape this test fails loudly rather than leaving its absence trivially "proven" everywhere.
     */
    @Test
    fun dayList_highlightsOnlyTheSuggestedRow_onAMixedDay() = runComposeUiTest {
        setContent {
            Themed {
                PlanContent(
                    rows = listOf(
                        taskRow("1", "Water the plants"),
                        recurringRow("h1", "Take a Walk"),
                    ),
                    isRefreshing = false,
                    onTaskClick = {},
                    today = today,
                    onStartFocus = {},
                    onWhatsNext = {},
                )
            }
        }

        // The one Task on the day is the ✦, and its row is inset by the marker ("anytime" is the deadline
        // subline of a Task with no time of day).
        assertTrue(
            titleGutter("Water the plants", "anytime") > 1f,
            "the suggested Task row draws the ✦ before its title",
        )
        // The Habit row is not, and cannot be, the suggestion — no marker, no card.
        assertTrue(
            abs(titleGutter("Take a Walk", "HABIT")) < 1f,
            "a row that is not the suggestion draws no ✦",
        )
    }

    /**
     * A day of nothing but recurring rows has no ✦ **anywhere** — not in the banner, and not on a row.
     *
     * [PlanContent] decides the per-row highlight with `suggested != null && row.task?.id == suggested.id`,
     * and that first clause is load-bearing rather than defensive. Kotlin accepts the shorter
     * `row.task?.id == suggested?.id`, which on this day compares `null` to `null` for every row — no ✦ was
     * picked, and no row has a Task to have been picked — so every row would draw as the highlighted card
     * and every divider between them would be suppressed: the whole list rendered as one undifferentiated
     * slab of "start here".
     *
     * The banner is gated separately (`if (suggested != null)`), so it is absent either way. Nothing above
     * the day list notices the difference, which is why the rows themselves have to be asserted on.
     */
    @Test
    fun dayList_highlightsNoRow_whenTheDayHoldsNothingStartable() = runComposeUiTest {
        setContent {
            Themed {
                PlanContent(
                    rows = listOf(
                        recurringRow("h1", "Take a Walk"),
                        recurringRow("c1", "Take shot", ItemKind.Chore),
                    ),
                    isRefreshing = false,
                    onTaskClick = {},
                    today = today,
                    onStartFocus = {},
                    onWhatsNext = {},
                )
            }
        }

        // Nothing here is startable, so there is nothing to suggest — the honest outcome rather than a
        // "Start" that leads to a Focus mode which cannot render a Habit.
        onNodeWithText("IF YOU'RE NOT SURE, START HERE").assertDoesNotExist()
        assertTrue(abs(titleGutter("Take a Walk", "HABIT")) < 1f, "the Habit row draws no ✦")
        assertTrue(abs(titleGutter("Take shot", "CHORE")) < 1f, "the Chore row draws no ✦")
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

    // --- the rendered choices ---

    /**
     * Regression (#375 review): the "What's next" screen draws only the plan's first three entries, so the
     * suggestion has to be chosen from those three. Picking across the whole plan let a Fire task below
     * the fold win, and `selected` — resolved against the rendered choices — then came back null: no card
     * selected, no ✦ chip, dead primary button.
     *
     * What this pins is the **screen**, not the precedence: that [WhatsNextContent] hands [suggestedTask]
     * its own `take(3)` rather than the whole day. Widening that `take` puts the fourth-placed Fire task
     * on screen and makes it the pick, and each of the three render assertions below independently
     * catches that. The precedence itself has its own tests in `:feature:plan`; the one bare assertion
     * here is a premise, not the subject — it establishes that this is a day where "pick from the whole
     * plan" and "pick from the cards" give different answers, which is what makes the case interesting.
     */
    @Test
    fun whatsNext_suggestsFromTheThreeCardsItDraws_notTheWholeDay() = runComposeUiTest {
        val plan = listOf(
            task("a", "Water the plants"),
            task("b", "Reply to Sam"),
            task("c", "File the taxes"),
            task("far-fire", "Call the plumber", priority = Priority.Fire),
        )
        // Premise: across the whole day, the rule picks the Fire task sitting fourth in the plan.
        assertEquals(TaskId("far-fire"), suggestedTask(plan)?.id)

        setContent {
            Themed {
                WhatsNextContent(tasks = plan, onBack = {}, onStartFocus = {})
            }
        }

        // It is not one of the three cards, so it is not on the screen at all...
        onNodeWithText("Call the plumber").assertDoesNotExist()
        // ...the screen opens on a card that IS drawn, so the primary button names it instead of sitting
        // dead behind a `selected` that resolved to nothing...
        onNodeWithText("Start · Water the plants").assertExists()
        // ...and the ✦ chip is on that first card rather than somewhere below the fold.
        val chip = onNodeWithContentDescription("Suggested").fetchSemanticsNode().positionInRoot.y
        val secondCard = onNodeWithText("Reply to Sam").fetchSemanticsNode().positionInRoot.y
        assertTrue(chip < secondCard, "the ✦ chip sits on the first of the three rendered cards")
    }
}
