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

    /**
     * [OccurrenceState.Unknown] is **not** resolved (ADR-0053 decision 4). The reading means this
     * device never synced that date, so the firing may well have been finished — claiming it resolved
     * would let an unsynced day count as a completion in every tally that filters on this flag, and
     * claiming otherwise is exactly the Missed-out-of-ignorance defect. Not knowing is not finishing.
     */
    @Test
    fun occurrenceStateUnknownIsNotResolved() {
        assertFalse(OccurrenceState.Unknown.isResolved)
    }

    /**
     * Seven members with `Unknown` **last**. The order is pinned because appending is the only safe
     * way to grow this enum: nothing persists an ordinal today (rows round-trip by `.name`), and this
     * assertion is what keeps a later member from being slipped into the middle on the assumption that
     * it still does not.
     */
    @Test
    fun occurrenceStateIsTheSixReadingsPlusUnknownAppendedLast() {
        assertEquals(
            listOf("Scheduled", "InProgress", "DoneOnTime", "DoneLate", "Skipped", "Missed", "Unknown"),
            OccurrenceState.entries.map { it.name },
        )
    }

    @Test
    fun occurrenceActionIsTheCoarseWriteTriple() {
        assertEquals(
            setOf("Start", "Complete", "Skip"),
            OccurrenceAction.entries.map { it.name }.toSet(),
        )
    }
}
