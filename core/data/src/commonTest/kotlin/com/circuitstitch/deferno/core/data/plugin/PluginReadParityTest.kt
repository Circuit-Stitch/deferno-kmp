package com.circuitstitch.deferno.core.data.plugin

import com.circuitstitch.deferno.core.data.item.toItem
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.RecurringDefinition
import com.circuitstitch.deferno.core.model.plugin.Anchor
import com.circuitstitch.deferno.core.model.recipe.KindRow
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
import com.circuitstitch.deferno.core.model.toDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.circuitstitch.deferno.core.model.Item as TreeRow

/**
 * The **sufficiency gate** on the read facade (#421): everything the two shipped projections show is
 * reachable from the plugin read of the same cached row.
 *
 * Phase 0's gate is round-trip identity — a kind row read into plugins and written back is the same
 * row — and it is green. That proves nothing is lost on the way *through* the recipe. It says nothing
 * about the question this phase raises, which is whether a reader can stop using the kind-shaped
 * projections at all. `Item` and `RecurringDefinition` are what every surface renders today; if the
 * plugin read cannot reproduce them, Phase 4 changes what a person sees while calling itself a
 * re-model, and the change is invisible because both readings are live at once.
 *
 * So each test here rebuilds a shipped projection from plugins alone ([asTreeRow],
 * [asRecurringDefinition]) and asserts it equals what the shipped mapper produces. A **non-vacuity
 * floor** runs beside them: a corpus in which some field is null everywhere would pass the equality
 * check while proving nothing about that field, so every field of both projections must take more than
 * one value across the corpus. It is the same guard the merged coverage gate carries — a hollowed-out
 * gate must not pass by measuring nothing.
 */
class PluginReadParityTest {

    // ── The two reconstructions ────────────────────────────────────────────────────────────────

    @Test
    fun theTreeRowIsReproducibleFromPluginsAloneOnEveryKind() {
        for (shape in PluginReadShapes.ALL) {
            assertEquals(
                shape.shippedTreeRow(),
                ParityRecipe.read(shape.row).asTreeRow(shape.kind),
                "${shape.label}: the tree row read through plugins disagrees with the one that ships",
            )
        }
    }

    @Test
    fun theDefinitionReadIsReproducibleFromPluginsAloneOnEveryRecurringKind() {
        val recurring = PluginReadShapes.ALL.filter { it.kind != ItemKind.Task }
        assertTrue(recurring.isNotEmpty(), "the corpus stopped carrying recurring kinds")

        for (shape in recurring) {
            assertEquals(
                shape.shippedDefinition(),
                ParityRecipe.read(shape.row).asRecurringDefinition(shape.kind),
                "${shape.label}: the definition read through plugins disagrees with the one that ships",
            )
        }
    }

    @Test
    fun everyRecordTheFacadeHandsOutIsValid() {
        // The two guarantees named fields gave for free and a plugin list has to buy back at runtime
        // (ADR-0055): at most one member of a family loads, and a plugin sits on the record its scope
        // names. The recipes are meant never to produce a set that fails either — asserted here rather
        // than assumed, because this is the first place recipe output reaches a caller.
        for (shape in PluginReadShapes.ALL) {
            assertEquals(
                emptyList(),
                ParityRecipe.read(shape.row).validate(),
                "${shape.label}: the read facade would emit an invalid record",
            )
        }
    }

    // ── The one field that is NOT sufficient, pinned as deliberate ─────────────────────────────

    @Test
    fun theRecurrenceCursorIsIndistinguishableFromADeadline() {
        // This file exists to prove sufficiency, so the one place it fails is worth stating outright
        // rather than hiding inside a kind branch in the reconstruction.
        //
        // `complete_by` is a deadline on a Task and a moving series CURSOR on a Habit or Chore, and the
        // parity recipe puts both into the same `Anchor.Deadline` because today's storage does. The
        // assertion below is that the two are byte-identical in the plugin read: same member, same
        // field, no discriminator. A reader has only the kind, which is exactly what the re-cut exists
        // to delete — so this is the gap the target recipe has to close, and `Anchor.Appointment` is
        // the worked example of how (it split the third claim on the same field off already).
        val task = PluginReadShapes.ALL.first { it.label == "task/full" }
        val habit = PluginReadShapes.ALL.first { it.label == "habit/live-series" }

        val deadline = assertIs<Anchor.Deadline>(ParityRecipe.read(task.row).anchor)
        val cursor = assertIs<Anchor.Deadline>(ParityRecipe.read(habit.row).anchor)

        assertEquals(
            deadline.completeBy,
            cursor.completeBy,
            "the corpus stopped giving a Task deadline and a Habit cursor the same instant",
        )
        assertEquals(
            deadline::class,
            cursor::class,
            "a Habit cursor is no longer read as the same Anchor member as a Task deadline — " +
                "if the target recipe split them, retire this test and drop the kind branch in asTreeRow",
        )

        // And the consequence, stated where it bites: told the wrong kind, the reconstruction reads a
        // Habit's cursor as a Task with a deadline it does not have.
        assertEquals(
            null,
            ParityRecipe.read(habit.row).asTreeRow(ItemKind.Task).recurrenceCursorAt,
            "the kind stopped being what decides whether complete_by is a cursor",
        )
    }

    // ── The non-vacuity floor ──────────────────────────────────────────────────────────────────

    @Test
    fun theCorpusExercisesEveryFieldOfTheTreeRow() {
        val projected = PluginReadShapes.ALL.map { it.shippedTreeRow() }
        for ((field, read) in TREE_ROW_FIELDS) {
            assertTrue(
                projected.map(read).distinct().size > 1,
                "the corpus no longer exercises Item.$field — it is constant across every shape, so " +
                    "the reconstruction gate proves nothing about it",
            )
        }
    }

    @Test
    fun theCorpusExercisesEveryFieldOfTheDefinitionRead() {
        val projected = PluginReadShapes.ALL.filter { it.kind != ItemKind.Task }.map { it.shippedDefinition() }
        for ((field, read) in DEFINITION_FIELDS) {
            assertTrue(
                projected.map(read).distinct().size > 1,
                "the corpus no longer exercises RecurringDefinition.$field — it is constant across " +
                    "every recurring shape, so the reconstruction gate proves nothing about it",
            )
        }
    }
}

// The shipped mappers, reached through the same `KindRow` dispatch the recipe uses. `toItem` is
// `internal` to core:data — this is the module's own test source set, which is the only reason the
// comparison can be made against the real mapper rather than a copy of it.

private fun PluginReadShapes.Shape.shippedTreeRow(): TreeRow = when (val held = row) {
    is KindRow.OfTask -> held.task.toItem()
    is KindRow.OfHabit -> held.habit.toItem()
    is KindRow.OfChore -> held.chore.toItem()
    is KindRow.OfEvent -> held.event.toItem()
}

private fun PluginReadShapes.Shape.shippedDefinition(): RecurringDefinition = when (val held = row) {
    is KindRow.OfHabit -> held.habit.toDefinition()
    is KindRow.OfChore -> held.chore.toDefinition()
    is KindRow.OfEvent -> held.event.toDefinition()
    is KindRow.OfTask -> error("a Task is not a definition — the caller filtered wrongly")
}

// One probe per field of each projection. Written out rather than reflected over, because KMP common
// has no reflection and because a hand-written list is what makes a NEW field visible: adding one to
// either projection without adding it here leaves it silently unchecked, and the compiler cannot say
// so. The failure message names the field, so a corpus that stops covering one says which.

private val TREE_ROW_FIELDS: List<Pair<String, (TreeRow) -> Any?>> = listOf(
    "id" to { it.id },
    "kind" to { it.kind },
    "title" to { it.title },
    "parentId" to { it.parentId },
    "sequence" to { it.sequence },
    "isTerminal" to { it.isTerminal },
    "descendantDone" to { it.descendantDone },
    "descendantTotal" to { it.descendantTotal },
    "source" to { it.source },
    "externalRef" to { it.externalRef },
    "blocked" to { it.blocked },
    "isBlocker" to { it.isBlocker },
    "blockedBy" to { it.blockedBy },
    "definitionState" to { it.definitionState },
    "recurrence" to { it.recurrence },
    "recurrenceCursorAt" to { it.recurrenceCursorAt },
    "seriesId" to { it.seriesId },
    "series" to { it.series },
)

private val DEFINITION_FIELDS: List<Pair<String, (RecurringDefinition) -> Any?>> = listOf(
    "id" to { it.id },
    "kind" to { it.kind },
    "title" to { it.title },
    "definitionState" to { it.definitionState },
    "description" to { it.description },
    "labels" to { it.labels },
    "recurrence" to { it.recurrence },
    "cursorAt" to { it.cursorAt },
    "seriesId" to { it.seriesId },
    "series" to { it.series },
    "parentId" to { it.parentId },
    "ref" to { it.ref },
    "hydration" to { it.hydration },
    "blocked" to { it.blocked },
    "isBlocker" to { it.isBlocker },
)
