package com.circuitstitch.deferno.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for the condensed domain state enums (ADR-0011, CONTEXT.md → "Item state"): the derived
 * helpers the data/UI layers branch on, and the exact constant sets the mapper (#18) must cover.
 */
class ItemStateTest {
    @Test
    fun workingStateTerminalIsDoneOrDropped() {
        assertTrue(WorkingState.Done.isTerminal)
        assertTrue(WorkingState.Dropped.isTerminal)
        assertFalse(WorkingState.Open.isTerminal)
        assertFalse(WorkingState.InProgress.isTerminal)
        assertFalse(WorkingState.InReview.isTerminal)
    }

    @Test
    fun workingStateHasTheFiveLifecycleConstants() {
        assertEquals(
            setOf("Open", "InProgress", "InReview", "Done", "Dropped"),
            WorkingState.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun definitionStateHasTheThreeLightSwitchConstants() {
        assertEquals(
            setOf("Active", "InReview", "Archived"),
            DefinitionState.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun occurrenceStateResolvedCoversDoneSkippedMissed() {
        assertTrue(OccurrenceState.DoneOnTime.isResolved)
        assertTrue(OccurrenceState.DoneLate.isResolved)
        assertTrue(OccurrenceState.Skipped.isResolved)
        assertTrue(OccurrenceState.Missed.isResolved)
        assertFalse(OccurrenceState.Scheduled.isResolved)
        assertFalse(OccurrenceState.InProgress.isResolved)
    }

    @Test
    fun occurrenceActionIsTheCoarseWriteTriple() {
        assertEquals(
            setOf("Start", "Complete", "Skip"),
            OccurrenceAction.entries.map { it.name }.toSet(),
        )
    }
}
