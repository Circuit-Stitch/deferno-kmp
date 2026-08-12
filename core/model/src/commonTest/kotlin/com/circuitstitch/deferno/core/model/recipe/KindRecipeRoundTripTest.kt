package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import com.circuitstitch.deferno.core.model.plugin.Progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The ADR-0056 **round-trip gate**: for every kind crossed with every field combination the wire can
 * carry, reading into plugins and writing back reproduces the input *unchanged*.
 *
 * ### What identity buys over equivalence
 *
 * The gate is `writeX(read(x)) == x`, not "the two agree about what matters". A recipe that dropped
 * a field on the way out and re-derived a plausible value on the way back would satisfy the weaker
 * claim and would silently rewrite rows in production. So the assertion is data-class equality over
 * the whole row, and the corpus varies every field the wire can carry — which is what makes a
 * dropped field impossible to miss rather than merely unlikely.
 *
 * ### Why the corpus is guarded rather than merely used
 *
 * A round-trip gate over an empty corpus is green and worthless, and a corpus that quietly shrinks
 * degrades the same way without ever going red. So the tests below are about the corpus itself: it
 * covers all four kinds, it does not silently lose shapes, its labels are unique enough to name a
 * failure, and — the one with real teeth — every axis a shape generator *declares* actually reaches
 * a shape. That last one is what fails when a field is added to a kind and forgotten here, which is
 * the way this gate would otherwise rot: silently, by covering less than it claims.
 */
class KindRecipeRoundTripTest {

    @Test
    fun readingIntoPluginsAndWritingBackReproducesTheInput() {
        // THE gate. Failures accumulate and are reported together, the way `RecurrenceCorpusTest`
        // reports the corpus: one field that stopped surviving typically breaks dozens of shapes at
        // once, and the first of them alone does not say which field it was.
        val failures = mutableListOf<String>()
        for (shape in KindShapes.ALL) {
            val roundTripped = shape.write(shape.read())
            if (roundTripped != shape.row) {
                failures += "── ${shape.label}\n  in:  ${shape.row}\n  out: $roundTripped"
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${KindShapes.ALL.size} shapes did not round-trip:\n\n" + failures.joinToString("\n\n"))
        }
    }

    @Test
    fun theListStaysSparseSoOneRowHasOnePluginList() {
        // Identity would still hold if the recipe loaded every Family unconditionally, but the
        // plugin list would then be a fixed-width record wearing a list's clothes — and two lists
        // meaning the same thing is exactly what makes "at most one member of a family" unenforceable
        // later.
        //
        // Asserting `plugins.all { it.saysSomething }` would be a tautology: that predicate is what
        // `sparse` filters on. So the assertion is over the DEGENERATE VALUES instead — a plugin
        // whose family reads the same whether or not it is loaded has no business being loaded, and
        // that comparison is independent of the filter that produced the list.
        for (shape in KindShapes.ALL) {
            val item = shape.read()
            val silent = item.plugins.filter { it == it.degenerate }
            assertEquals(emptyList(), silent, "${shape.label} loaded a plugin equal to its degenerate value")
        }
        // And the strongest form of it: a row at every wire default carries its Core and exactly one
        // plugin — the lifecycle.
        //
        // That one is not a leak. `Lifecycle.Unstated` means *no lifecycle at all*, which is a shape
        // the plugin model admits and no four-kind row is: a Task with `workingState = Open` has
        // genuinely said where it is. The write direction supplies `Open` as the wire default for the
        // other case, so both round-trip — and collapsing them here would be the model pretending it
        // knows a kind's default, which is exactly the knowledge the recipe layer exists to hold.
        val bare = ParityRecipe.read(
            KindShapes.ALL.firstNotNullOf { it.row as? KindRow.OfTask }.task.copy(
                labels = emptyList(), completeBy = null, deadlineTimeOfDay = null, targetDate = null,
                priority = Priority.Normal, productive = null,
                desire = null, pinned = false, finishedAt = null, description = null, nextTaskId = null,
                blocked = false, isBlocker = false, blockedBy = emptyList(), external = null,
                attachmentCount = 0, attachmentTotalSize = 0,
                workingState = WorkingState.Open,
            ),
        )
        assertEquals(
            listOf(Progress(Lifecycle.Working(WorkingState.Open))),
            bare.plugins,
            "a row at every wire default should carry its lifecycle and nothing else",
        )
    }

    @Test
    fun everyReadItemIsValid() {
        // Placement and exclusivity are runtime checks (ADR-0055's stated cost), so the recipe is
        // the first thing that could violate them at scale — two members of one family loaded, or a
        // definition plugin landing on the wrong record.
        val problems = KindShapes.ALL
            .map { it to it.read().validate() }
            .filter { (_, p) -> p.isNotEmpty() }
            .map { (shape, p) -> "${shape.label}: $p" }
        assertEquals(emptyList(), problems, "the recipe produced invalid plugin lists")
    }

    @Test
    fun everyKindContributesShapesToTheGate() {
        // Over `ItemKind.entries` rather than a hand-listed four, so a fifth kind cannot be added
        // without this failing — the runtime half of the exhaustive `when` in `KindShapes.shapesOf`.
        for (kind in ItemKind.entries) {
            val shapes = KindShapes.ALL.filter { it.kind == kind }
            assertTrue(shapes.isNotEmpty(), "$kind contributes no shapes; the gate would pass vacuously for it")
        }
    }

    @Test
    fun theCorpusDoesNotSilentlyShrink() {
        // Literal expected counts, the same guard `RecurrenceCorpusTest.everyPinnedSemanticStillHasACase`
        // uses: deleting an axis makes the gate cover less while still passing, and a count is the
        // only thing that notices. Update these deliberately when an axis is added, never to make a
        // red test green.
        val counted = ItemKind.entries.associateWith { kind -> KindShapes.ALL.count { it.kind == kind } }
        assertEquals(
            mapOf(
                ItemKind.Task to 198,
                ItemKind.Habit to 117,
                ItemKind.Chore to 225,
                ItemKind.Event to 207,
            ),
            counted,
            "the shape corpus changed size; if that was deliberate, update these counts",
        )
    }

    @Test
    fun everyAxisActuallyVariesTheRow() {
        // The corpus is deliberately redundant: each family's product includes the combination where
        // every one of its axes sits at the baseline value, so the plain baseline row is regenerated
        // once per family. That redundancy is free (these are data-class copies) and it keeps the
        // labels honest — every declared axis value gets its own named shape.
        //
        // What it must not hide is an axis whose choices produce the *same* row, which reads as
        // coverage and is not. Counting DISTINCT rows is what notices: adding an inert axis raises
        // the total above and leaves this number alone.
        val distinct = ItemKind.entries.associateWith { kind ->
            KindShapes.ALL.filter { it.kind == kind }.map { it.row }.toSet().size
        }
        assertEquals(
            mapOf(
                ItemKind.Task to 187,
                ItemKind.Habit to 109,
                ItemKind.Chore to 217,
                ItemKind.Event to 199,
            ),
            distinct,
            "the number of distinct rows changed; an axis may have stopped varying anything",
        )
    }

    @Test
    fun everyShapeIsNameable() {
        // A failure in the identity sweep reports a label. Duplicate labels make that report
        // ambiguous, which is the difference between a diff and a hunt.
        val labels = KindShapes.ALL.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "shape labels are not unique")
        assertTrue(labels.all { it.isNotBlank() }, "a shape has a blank label")
    }

    @Test
    fun everyDeclaredAxisValueReachesAShape() {
        // The teeth. Each label is `<Kind>/<baseline>/<family>:<axis>+<axis>…`, so the set of axis
        // names present in the corpus is readable from the labels alone. An axis declared in
        // `KindShapes` and producted into nothing would be a case that exists in the source and not
        // in the sweep.
        val declared = mapOf(
            ItemKind.Task to setOf(
                "minimal", "saturated", "tombstoned",
                "no-desc", "desc", "no-labels", "labels", "normal", "fire-pinned", "backlog",
                "no-attachments", "attachments",
                "unanchored", "all-day-deadline", "timed-deadline", "no-target", "target",
                "Open", "InProgress", "InReview", "Done", "Dropped",
                "unfinished", "finished", "no-productive", "productive",
                "no-desire", "desire-0", "desire-mid", "desire-1",
                "unblocked", "blocked", "is-blocker", "native", "imported",
                "no-successor", "successor",
            ),
            ItemKind.Habit to setOf(
                "minimal", "saturated", "tombstoned",
                "no-desc", "desc", "no-labels", "labels", "normal", "fire-pinned", "backlog",
                "unanchored", "all-day-deadline", "timed-deadline", "no-target", "target",
                "no-rule", "daily", "every-2-until", "elided-series", "series-inputs",
                "Active", "InReview", "Archived",
                "unblocked", "blocked", "is-blocker",
            ),
            ItemKind.Chore to setOf(
                "minimal", "saturated", "tombstoned",
                "no-desc", "desc", "no-labels", "labels", "normal", "fire-pinned", "backlog",
                "unanchored", "all-day-deadline", "timed-deadline", "no-target", "target",
                "no-rule", "daily", "every-2-until", "rolling", "fixed", "unmodelled-mode",
                "elided-series", "series-inputs",
                "Active", "InReview", "Archived",
                "unblocked", "blocked", "is-blocker",
            ),
            ItemKind.Event to setOf(
                "minimal", "saturated", "tombstoned",
                "no-desc", "desc", "no-labels", "labels", "normal", "fire-pinned", "backlog",
                "no-window", "start-only", "start-and-end",
                "all-day-times", "start-time", "both-times", "all-day-false", "all-day-true",
                "no-target", "target",
                "no-rule", "daily", "every-2-until", "elided-series", "series-inputs",
                "Active", "InReview", "Archived",
                "unblocked", "blocked", "is-blocker",
            ),
        )

        for ((kind, expected) in declared) {
            val present = KindShapes.ALL.filter { it.kind == kind }.flatMap { axisNames(it.label) }.toSet()
            assertEquals(emptySet(), expected - present, "$kind declares axis values that reach no shape")
            assertEquals(emptySet(), present - expected, "$kind produced axis values this test does not know about")
        }
    }

    // ── Reading a label ────────────────────────────────────────────────────────────────────────

    /** `Task/saturated/temporal:timed-deadline+target` → `[saturated, timed-deadline, target]`. */
    private fun axisNames(label: String): List<String> {
        val afterKind = label.substringAfter('/')
        val baseline = afterKind.substringBefore('/')
        val picks = afterKind.substringAfter('/').substringAfter(':')
        return listOf(baseline) + picks.split('+')
    }
}
