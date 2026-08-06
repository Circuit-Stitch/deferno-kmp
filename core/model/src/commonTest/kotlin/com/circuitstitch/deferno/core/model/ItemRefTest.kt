package com.circuitstitch.deferno.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for [ItemRef] — the `(kind, id)` pair #383 promotes to a type so the Item tree's open
 * callback stops discarding the kind it already had.
 *
 * The one case that is a *guard* rather than a convenience is [ItemRef.taskId]: it is the only place a
 * [TaskId] is minted from a ref, and it must be `null` for the three recurring kinds. A recurring id
 * smuggled down a `TaskId`-typed seam applies nothing locally and then dies as a 404 the caller reads
 * as success — the silent-loss shape the write seams already warn about. Every assertion below runs
 * over `ItemKind.entries` rather than a hand-listed three, so a fifth kind cannot be added without an
 * answer here.
 */
class ItemRefTest {

    private val id = "3f2b1c9e-0000-4000-8000-000000000001"

    // ── taskId: the guard ────────────────────────────────────────────────────────────────────

    @Test
    fun taskIdIsMintedForATaskAndForNothingElse() {
        assertEquals(TaskId(id), ItemRef(id, ItemKind.Task).taskId)
        for (kind in ItemKind.entries - ItemKind.Task) {
            assertNull(ItemRef(id, kind).taskId, "a $kind id must never mint a TaskId")
        }
    }

    /**
     * Exactly one kind converts, stated as a set so the guard cannot be widened by adding a kind that
     * happens to fall on the wrong side of an `if`.
     */
    @Test
    fun exactlyOneKindConvertsToATypedTaskId() {
        assertEquals(
            setOf(ItemKind.Task),
            ItemKind.entries.filter { ItemRef(id, it).taskId != null }.toSet(),
        )
    }

    // ── isTask / isDefinition ────────────────────────────────────────────────────────────────

    @Test
    fun everyRefIsEitherATaskOrADefinitionAndNeverBoth() {
        for (kind in ItemKind.entries) {
            val ref = ItemRef(id, kind)
            assertEquals(kind == ItemKind.Task, ref.isTask, "isTask disagreed for $kind")
            // The two are a partition, not two independent flags: a caller branching on one is
            // guaranteed to have covered the other arm.
            assertEquals(!ref.isTask, ref.isDefinition, "isDefinition is not the complement for $kind")
        }
    }

    @Test
    fun theThreeRecurringKindsAreTheDefinitions() {
        assertEquals(
            setOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event),
            ItemKind.entries.filter { ItemRef(id, it).isDefinition }.toSet(),
        )
    }

    // ── The id itself ────────────────────────────────────────────────────────────────────────

    /**
     * Same posture as [TaskId] and its siblings: a blank id is a broken address, and it must fail where
     * it is constructed rather than several seams later as a route that resolves to nothing.
     */
    @Test
    fun aBlankIdIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { ItemRef("", ItemKind.Task) }
        assertFailsWith<IllegalArgumentException> { ItemRef("   ", ItemKind.Habit) }
    }

    @Test
    fun theIdIsCarriedVerbatimAsTheRawWireUuid() {
        // Deliberately a raw String and not a kind-typed id: the forest nests children under parents of
        // any kind, so a kind-typed id cannot express a tree edge (see [Item]'s KDoc).
        assertEquals(id, ItemRef(id, ItemKind.Event).id)
    }

    // ── Item.ref() ───────────────────────────────────────────────────────────────────────────

    /**
     * The round trip that is the whole fix: a tree row's kind survives into the navigation intent, so
     * the detail slot has something to open a Habit/Chore/Event *with* rather than only a Task id.
     */
    @Test
    fun aRowsRefCarriesBothHalvesOfItsAddressForEveryKind() {
        for (kind in ItemKind.entries) {
            val row = Item(id = id, kind = kind, title = "stretch")
            assertEquals(ItemRef(id, kind), row.ref(), "the ref lost a half for $kind")
            assertEquals(row.id, row.ref().id)
            assertEquals(row.kind, row.ref().kind)
        }
    }

    @Test
    fun aRefIsValueEqualOnBothHalves() {
        // A data class, so two refs to the same row are interchangeable as map keys — but the kind is
        // part of the identity, which is exactly what stops a Task and a Habit id colliding.
        assertEquals(ItemRef(id, ItemKind.Chore), ItemRef(id, ItemKind.Chore))
        assertTrue(ItemRef(id, ItemKind.Chore) != ItemRef(id, ItemKind.Habit))
    }
}
