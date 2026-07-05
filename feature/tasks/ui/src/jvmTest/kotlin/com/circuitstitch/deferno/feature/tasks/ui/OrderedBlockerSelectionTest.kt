package com.circuitstitch.deferno.feature.tasks.ui

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.feature.tasks.BlockedByCandidate
import com.circuitstitch.deferno.feature.tasks.BlockedByPickerState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The picker's checked-set → ordered blocker list mapping (#291): kept edges preserve the server's
 * existing order, new ones append in candidate order, and an untouched selection round-trips unchanged
 * (so a no-op Save can't reorder the server's list).
 */
class OrderedBlockerSelectionTest {

    private fun picker(current: List<String>, candidates: List<String>) = BlockedByPickerState(
        itemId = "t",
        itemTitle = "t",
        current = current,
        candidates = candidates.map { BlockedByCandidate(it, "title-$it", ItemKind.Task) },
    )

    @Test
    fun untouchedSelectionRoundTripsTheExistingOrder() {
        val p = picker(current = listOf("b", "a"), candidates = listOf("a", "b", "c"))
        assertEquals(listOf("b", "a"), orderedBlockerSelection(p, setOf("a", "b")))
    }

    @Test
    fun newChecksAppendInCandidateOrderAfterTheKeptEdges() {
        val p = picker(current = listOf("b"), candidates = listOf("a", "b", "c", "d"))
        assertEquals(listOf("b", "a", "d"), orderedBlockerSelection(p, setOf("b", "d", "a")))
    }

    @Test
    fun uncheckedEdgesDropAndAnEmptySelectionClearsAll() {
        val p = picker(current = listOf("a", "b"), candidates = listOf("a", "b", "c"))
        assertEquals(listOf("b"), orderedBlockerSelection(p, setOf("b")))
        assertEquals(emptyList(), orderedBlockerSelection(p, emptySet()))
    }
}
