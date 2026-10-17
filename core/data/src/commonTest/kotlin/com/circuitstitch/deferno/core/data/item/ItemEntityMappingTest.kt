package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.database.sql.ItemEntity
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.recipe.KindRow
import com.circuitstitch.deferno.core.model.recipe.KindShapes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The **storage-fidelity gate** (#422): a kind row written to `itemEntity` and read straight back is
 * the same row.
 *
 * ### Why this gate exists now
 *
 * Storage is two translations. A stored row becomes a [KindRow] through `ItemEntityMapping.kt`, and
 * that becomes a plugin-shaped record through `KindRecipe`. Only the second was gated before, by
 * `KindRecipeRoundTripTest`. An identity on one half of a composition says nothing about the whole, so
 * a column dropped here would corrupt the local source of truth silently — and the four per-kind
 * mappings this file replaced each had their own narrower version of this test.
 *
 * ### It sweeps the same corpus the recipe round trip does
 *
 * `KindShapes` is compiled into this module's tests as well as `core:model`'s (see the `srcDir` in the
 * build file). Two corpora would drift, and the weaker one would quietly decide what the gate covers.
 * The corpus is combinatorial within a plugin family and crosses three baselines, which is what makes
 * a dropped field impossible to miss rather than merely unlikely — the argument is in
 * `KindRecipeRoundTripTest`'s KDoc.
 *
 * ### The series inputs are handed back rather than stored
 *
 * `seriesInputsEntity` is a separate table, because a series' exdates are an unbounded list. The
 * mapping neither writes nor reads it, so the round trip supplies the row's own inputs on the way back
 * in — the same thing [SqlDelightItemLocalStore] does with what it read from that table.
 * `SeriesInputsLocalStoreTest` gates the table itself.
 */
class ItemEntityMappingTest {

    // ── The gate ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyKindRowWrittenToTheRowAndReadBackIsUnchanged() {
        // Failures accumulate and are reported together, the way the recipe round trip reports its
        // corpus: one column that stopped surviving typically breaks dozens of shapes at once, and the
        // first of them alone does not say which column it was.
        val failures = mutableListOf<String>()
        for (shape in KindShapes.ALL) {
            val roundTripped = shape.row.toEntity().toKindRow(shape.row.seriesInputs)
            if (roundTripped != shape.row) {
                failures += "── ${shape.label}\n  in:  ${shape.row}\n  out: $roundTripped"
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${KindShapes.ALL.size} shapes did not survive the row:\n\n" +
                    failures.joinToString("\n\n"),
            )
        }
    }

    @Test
    fun everyKindReachesTheGate() {
        // A sweep over an empty corpus is green and worthless, and this one reads its shapes from
        // another module's sources — so "the corpus arrived at all" is worth asserting rather than
        // assuming. `KindRecipeRoundTripTest` guards the corpus's size; this guards that this module
        // sees it.
        for (kind in ItemKind.entries) {
            val shapes = KindShapes.ALL.filter { it.kind == kind }
            assertTrue(shapes.isNotEmpty(), "$kind contributes no shapes; the gate would pass vacuously for it")
        }
    }

    // ── The one deliberate asymmetry ───────────────────────────────────────────────────────────

    @Test
    fun aRowNamingNoKnownKindReadsAsNullWhereEveryOtherDecodeDegrades() {
        // The single place this mapping does not follow its own defensive-decode rule. There is no safe
        // kind to fall back to: reading a Habit as a Task would give it a working state it has never had
        // and drop the rule that makes it a series. So the row is unreadable by this build rather than
        // degraded, and the store drops it from the list.
        assertNull(taskEntity().copy(kind = "Sprint").toKindRow())
        assertNull(taskEntity().copy(kind = "").toKindRow())
        // The wire token, not the Kotlin constant, would be just as unreadable — the column stores
        // `ItemKind.name` and nothing normalises case on the way in.
        assertNull(taskEntity().copy(kind = "task").toKindRow())
    }

    @Test
    fun everyOtherUnrecognisedTokenDegradesToItsDefaultInsteadOfThrowing() {
        // The contrast case for the asymmetry above, and the forward-additive guarantee: a value written
        // by a newer build can never crash an older reader.
        assertEquals(WorkingState.Open, taskEntity().copy(working_state = "Frobnicated").task().workingState)
        assertEquals(HydrationState.Summary, taskEntity().copy(hydration_state = "Partial").task().hydration)
        assertEquals(Priority.Normal, taskEntity().copy(priority = "Volcanic").task().priority)
        // An unrecognised provenance source degrades to a native item rather than a half-built ref…
        assertNull(taskEntity().copy(external_source = "bitbucket", external_id = "x#1").task().external)
        // …and so does a source with no id, which is a malformed pair rather than an unknown one.
        assertNull(taskEntity().copy(external_source = "GitHub", external_id = null).task().external)
    }

    // ── The union table's own rule ─────────────────────────────────────────────────────────────

    @Test
    fun aColumnAKindDoesNotHaveIsNullOnThatKindsRows() {
        // What keeps one table from becoming ambiguous. Each encode starts from a row with every
        // kind-specific column at NULL or its default and copies in only the columns its own kind owns,
        // so a Chore's cadence mode cannot appear on a Habit's row and the decode never has to guess.
        //
        // The saturated baselines are the ones with teeth here: they carry a non-default value in every
        // field their own kind declares, so a column leaking across kinds shows up as a value rather
        // than as a NULL that was going to be NULL anyway.
        val habit = saturated(ItemKind.Habit).toEntity()
        assertNull(habit.working_state)
        assertNull(habit.finished_at)
        assertNull(habit.next_task_id)
        assertNull(habit.productive)
        assertNull(habit.desire)
        assertNull(habit.blocked_by)
        assertNull(habit.external_source)
        assertNull(habit.attachment_count)
        assertNull(habit.descendant_total)
        assertEquals("", habit.child_ids)
        // A Habit never carries a cadence mode — that is the one field distinguishing it from a Chore.
        assertNull(habit.cadence_mode)
        assertNull(habit.end_time)
        assertEquals(0L, habit.all_day)

        val task = saturated(ItemKind.Task).toEntity()
        assertNull(task.definition_state)
        assertNull(task.series_id)
        assertNull(task.recurrence_type)
        assertEquals("", task.recurrence_days)
        assertNull(task.cadence_mode)
        assertNull(task.start_time_of_day)
        assertEquals(0L, task.all_day)

        val event = saturated(ItemKind.Event).toEntity()
        assertNull(event.cadence_mode)
        assertNull(event.deadline_time_of_day)
        assertNull(event.working_state)
    }

    @Test
    fun everyRowStoresItsOwnKindInTheDiscriminator() {
        // The column the store reads back to know which endpoint a row round-trips to. It is sync
        // bookkeeping and no part of the record, which is exactly why nothing else asserts it.
        for (shape in KindShapes.ALL) {
            assertEquals(shape.kind.name, shape.row.toEntity().kind, shape.label)
        }
    }

    // ── The encodings the corpus cannot reach ──────────────────────────────────────────────────

    @Test
    fun emptyListColumnsDecodeToEmptyListsRatherThanAListHoldingOneBlank() {
        // The `\n`-join is only lossless if the empty case is special-cased on the way out. `"".split()`
        // yields `[""]`, which would give every unlabelled row a phantom empty label.
        val decoded = taskEntity().copy(labels = "", child_ids = "", blocked_by = "").task()
        assertEquals(emptyList(), decoded.labels)
        assertEquals(emptyList(), decoded.children)
        assertEquals(emptyList(), decoded.blockedBy)
    }

    @Test
    fun aPreMigrationNullColumnDecodesToTheDomainDefault() {
        // Nothing in the corpus produces a NULL in these columns, because the encode always writes them.
        // A row an older build cached can hold one, and it has to read as the domain default rather than
        // crash or fabricate.
        val decoded = taskEntity().copy(
            blocked = null,
            is_blocker = null,
            blocked_by = null,
            attachment_count = null,
            attachment_total_size = null,
            descendant_done = null,
            descendant_total = null,
            priority = null,
            target_date = null,
        ).task()
        assertEquals(false, decoded.blocked)
        assertEquals(false, decoded.isBlocker)
        assertEquals(emptyList(), decoded.blockedBy)
        assertEquals(0, decoded.attachmentCount)
        assertEquals(0L, decoded.attachmentTotalSize)
        assertNull(decoded.descendantDone)
        assertNull(decoded.descendantTotal)
        assertEquals(Priority.Normal, decoded.priority)
        assertNull(decoded.targetDate)
    }

    @Test
    fun booleansStoreAsOneAndZeroAndAnyNonZeroReadsAsTrue() {
        val task = assertIs<KindRow.OfTask>(saturated(ItemKind.Task)).task
        assertEquals(1L, KindRow.OfTask(task.copy(pinned = true)).toEntity().pinned)
        assertEquals(0L, KindRow.OfTask(task.copy(pinned = false)).toEntity().pinned)
        // Defensive on the way in: the column is a plain INTEGER, so a non-canonical truthy value reads
        // as true rather than as false.
        assertTrue(taskEntity().copy(pinned = 2L).task().pinned)
        assertTrue(saturated(ItemKind.Event).toEntity().copy(all_day = 7L).event().allDay)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────

    /** The corpus's saturated baseline for [kind] — every field its kind declares at a non-default. */
    private fun saturated(kind: ItemKind): KindRow =
        KindShapes.ALL.first { it.kind == kind && it.label.substringAfter('/').startsWith("saturated") }.row

    /** A valid stored Task row to perturb one column of, taken from the corpus rather than hand-built. */
    private fun taskEntity(): ItemEntity = saturated(ItemKind.Task).toEntity()

    private fun ItemEntity.task() = assertIs<KindRow.OfTask>(toKindRow()).task

    private fun ItemEntity.event() = assertIs<KindRow.OfEvent>(toKindRow()).event

    /**
     * The expansion inputs this row carries, which live in `seriesInputsEntity` and never in the item
     * row. `null` on a Task, which has no series.
     */
    private val KindRow.seriesInputs: SeriesInputs?
        get() = when (this) {
            is KindRow.OfTask -> null
            is KindRow.OfHabit -> habit.series
            is KindRow.OfChore -> chore.series
            is KindRow.OfEvent -> event.series
        }
}
