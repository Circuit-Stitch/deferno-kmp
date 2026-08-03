package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.feature.tasks.ItemRow
import com.circuitstitch.deferno.feature.tasks.MoveMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The local segmented filter's three arms (#386) — the Compose half of a defect that shipped on all four
 * platforms. Compose's variant was the worst of the three: "In today" fell into the `else` arm, so it was
 * byte-identical to **All** and the segment *widened* rather than narrowing.
 *
 * The predicate is asserted twice on purpose: once directly on [visibleTreeRows] (every arm, cheap), and
 * once through the real [ItemTreeContent] by clicking the segment a user clicks — the wiring between the
 * two is exactly what was broken, so a pure-function test alone would not have caught it. This one edit
 * covers **Android and desktop**, which render the same content.
 *
 * It has since grown two more sections over the same rendered tree, both about what a row *says* rather
 * than which rows show: where kind is announced (#384's carrier move off the dot and onto the title), and
 * that a recurring row's cadence subtitle is wired in with its screen-reader form.
 */
@OptIn(ExperimentalTestApi::class)
class ItemTreeFilterTest {

    private fun row(
        id: String,
        title: String,
        kind: ItemKind = ItemKind.Task,
        terminal: Boolean = false,
        hasChildren: Boolean = false,
    ) = ItemRow(
        item = Item(id = id, kind = kind, title = title, isTerminal = terminal),
        depth = 0,
        hasChildren = hasChildren,
        isExpanded = false,
    )

    /** A planned Task, a Habit firing today, an unplanned Task, a finished one — and a **branch**. */
    private val planned = row("t-1", "Water the plants")
    private val habitFiringToday = row("h-1", "Morning run", kind = ItemKind.Habit)
    private val unplanned = row("t-2", "Someday, maybe")
    private val finished = row("t-3", "Schedule the post", terminal = true)

    /**
     * A collapsed **parent**, added for the a11y assertions below (#384): every other fixture row is a
     * leaf, so the branch arm of [TreeNode] — the one whose Button semantics used to swallow the row's
     * kind — had no coverage at all here. A Habit parent specifically: "a Habit parent and a Task parent
     * are indistinguishable" was the defect.
     */
    private val habitParent = row("h-2", "Weekly review", kind = ItemKind.Habit, hasChildren = true)

    private val rows = listOf(planned, habitFiringToday, unplanned, finished, habitParent)
    private val inToday = setOf("t-1", "h-1")

    // --- the predicate ---

    /**
     * The heart of #386. A Habit with a firing today is non-terminal, so "Active" keeps it *and* keeps
     * everything else non-terminal — which is exactly why the two segments were indistinguishable. They
     * must now disagree.
     */
    @Test
    fun inTodayDiffersFromActiveForARecurringItemWithAFiringToday() {
        val today = visibleTreeRows(rows, filterIndex = 0, inTodayIds = inToday)
        val active = visibleTreeRows(rows, filterIndex = 1, inTodayIds = inToday)

        assertEquals(listOf("t-1", "h-1"), today.map { it.item.id }, "In today = the joined set")
        assertEquals(
            listOf("t-1", "h-1", "t-2", "h-2"),
            active.map { it.item.id },
            "Active = every non-terminal row",
        )
        assertEquals(true, today != active, "the two segments are no longer the same predicate")
    }

    @Test
    fun activeHidesTerminalRows() {
        assertEquals(
            listOf("t-1", "h-1", "t-2", "h-2"),
            visibleTreeRows(rows, filterIndex = 1, inTodayIds = inToday).map { it.item.id },
        )
    }

    @Test
    fun allKeepsEveryRowIncludingTerminals() {
        assertEquals(rows, visibleTreeRows(rows, filterIndex = 2, inTodayIds = inToday))
    }

    /** Guards the regression this issue *is*: segment 0 must never fall through to "everything". */
    @Test
    fun inTodayWithAnEmptyJoinNarrowsToNothingRatherThanWideningToAll() {
        assertEquals(emptyList(), visibleTreeRows(rows, filterIndex = 0, inTodayIds = emptySet()))
    }

    /** An id in the set with no matching row is simply not a row — never a phantom. */
    @Test
    fun anInTodayIdWithNoLoadedRowContributesNothing() {
        assertEquals(
            listOf("t-1"),
            visibleTreeRows(rows, filterIndex = 0, inTodayIds = setOf("t-1", "not-loaded")).map { it.item.id },
        )
    }

    // --- through the real View ---

    @Test
    fun selectingInTodayNarrowsTheRenderedTreeToTheJoinedSet() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = inToday) } }
        onNodeWithText("In today").performClick()

        onNodeWithText("Water the plants").assertIsDisplayed()
        onNodeWithText("Morning run").assertIsDisplayed()
        onNodeWithText("Someday, maybe").assertDoesNotExist()
        onNodeWithText("Schedule the post").assertDoesNotExist()
    }

    @Test
    fun selectingActiveKeepsTheUnplannedRowThatInTodayHid() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = inToday) } }
        onNodeWithText("Active").performClick()

        onNodeWithText("Someday, maybe").assertIsDisplayed()
        onNodeWithText("Schedule the post").assertDoesNotExist()
    }

    /** The default segment is still All — a filter that narrows on first paint would be a surprise. */
    @Test
    fun theTreeStillOpensOnAllWithEveryRowShowing() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet()) } }

        onNodeWithText("Someday, maybe").assertIsDisplayed()
        onNodeWithText("Schedule the post").assertIsDisplayed()
    }

    /**
     * An empty "In today" is not "everything here is done" — nothing is done, nothing is simply on today's
     * plan. The segment gets its own body copy rather than the shared filtered one.
     */
    @Test
    fun anEmptyInTodayExplainsItselfInsteadOfClaimingEverythingIsDone() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet()) } }
        onNodeWithText("In today").performClick()

        onNodeWithText("Nothing here is on today's plan. Switch to “All” to see everything.").assertIsDisplayed()
    }

    // --- the row's kind, for TalkBack (#384 — the carrier move that finishes what #386/#393 started) ---

    /**
     * Kind rides the **title**, not the dot. #386 named the leaf dot and stopped there, which left the
     * branch arm — a Button, hence its own merging node outside the row's `combinedClickable` — naming
     * no kind at all, so a Habit parent and a Task parent sounded identical. Both row shapes must now
     * announce it, in Apple's "{title}, {kind}" order (#393).
     */
    @Test
    fun everyRowNamesItsKindOnTheTitleWhateverItsShape() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet()) } }

        assertEquals(1, labelledNodes("Water the plants, task"), "a Task leaf")
        assertEquals(1, labelledNodes("Morning run, habit"), "a Habit leaf")
        assertEquals(1, labelledNodes("Weekly review, habit"), "a Habit BRANCH — the arm #386 missed")
    }

    /**
     * The corollary of the move: the dot is decorative again in *every* row state, so the bare kind word
     * is no longer a content description anywhere. Anchored on an exact match, which is the point — the
     * old assertion passed on the standalone "habit" the dot carried.
     */
    @Test
    fun theKindDotItselfIsSilentEverywhere() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet()) } }

        assertEquals(0, anyNodeLabelled("habit"))
        assertEquals(0, anyNodeLabelled("task"))
    }

    /**
     * Move mode calms the list — the row body goes inert and the dots stay decorative — but the title is
     * NOT silenced, and that inverts the old expectation on purpose: you should be able to hear what you
     * are dragging. So the dot is silent while the title still speaks its kind.
     */
    @Test
    fun moveModeSilencesTheDotButNotTheTitle() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet(), moveMode = MoveMode("t-1", true, true, true, true)) } }

        assertEquals(0, anyNodeLabelled("habit"), "the dot stays decorative")
        assertEquals(0, anyNodeLabelled("task"))
        assertEquals(1, labelledNodes("Water the plants, task"), "the lifted row still says what it is")
        assertEquals(1, labelledNodes("Weekly review, habit"))
    }

    // --- the recurring row's cadence subtitle (#384), as WIRED into the row ---

    /**
     * The wiring, not the phrasing — [RecurrenceSummaryTest] owns every cadence/bound/cursor arm. What
     * this pins is that the row actually renders the line, in its *spoken* form. An Archived definition
     * is used because it is the one shape with a rule and no next-due clause, so the expectation is one
     * stable phrase with no dependence on today.
     *
     * The assertion goes through the content description rather than `onNodeWithText`, and it has to:
     * the subtitle clears its own semantics so the spoken form can replace the written one, which leaves
     * the visible string unreachable from the semantics tree entirely. The `item_tree_recurring_*`
     * Roborazzi goldens are what witness the visible line.
     */
    @Test
    fun aRecurringRowRendersItsCadenceAndAnnouncesItWithItsVerb() = runComposeUiTest {
        val archivedHabit = ItemRow(
            item = Item(
                id = "h-9",
                kind = ItemKind.Habit,
                title = "Weekly review",
                definitionState = DefinitionState.Archived,
                recurrence = Recurrence(Cadence.Weekly(listOf("Mon"))),
            ),
            depth = 0,
            hasChildren = false,
            isExpanded = false,
        )
        setContent { Themed { Tree(inTodayIds = emptySet(), rows = listOf(archivedHabit, planned)) } }

        assertEquals(1, labelledNodes("Repeats Weekly on Mon"), "the subtitle speaks its verb, not the bare adverb")
        assertEquals(1, labelledNodes("Weekly review, habit"), "and the title still carries the kind")
    }

    /** The control: not one row in the default fixture carries a rule, so not one wears a subtitle. */
    @Test
    fun aRowWithNoRecurrenceRuleWearsNoSubtitleAtAll() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet()) } }

        assertEquals(
            0,
            onAllNodesWithContentDescription("Repeats", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    /**
     * Nodes carrying [label] on the **merged** tree — i.e. what a screen reader would actually announce
     * when it lands on the row, not merely what exists somewhere in the semantics.
     *
     * The distinction is the whole defect. #386 put kind on the leaf dot, which a leaf row's
     * `combinedClickable` absorbs — but a *branch*'s fold node is itself a merging node (a focusable
     * Button), so it is never absorbed, and the parent row announced two nodes with no kind between them.
     * An unmerged assertion cannot tell that apart from a fix: it passes just as happily when the label is
     * stranded on a node nothing focuses. Every positive assertion here therefore goes through the merged
     * tree, and only the "nothing carries the bare kind word" checks use [anyNodeLabelled], where the
     * broader search is the stronger claim.
     */
    private fun ComposeUiTest.labelledNodes(label: String): Int =
        onAllNodesWithContentDescription(label).fetchSemanticsNodes().size

    /** Nodes carrying [label] **anywhere**, merged or not — used to prove an absence, never a presence. */
    private fun ComposeUiTest.anyNodeLabelled(label: String): Int =
        onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().size

    @Composable
    private fun Tree(
        inTodayIds: Set<String>,
        moveMode: MoveMode? = null,
        rows: List<ItemRow> = this.rows,
    ) {
        ItemTreeContent(
            rows = rows,
            isRefreshing = false,
            onToggleExpand = { _, _ -> },
            onOpenDetail = { _, _ -> },
            onRefresh = {},
            inTodayIds = inTodayIds,
            moveMode = moveMode,
        )
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}
