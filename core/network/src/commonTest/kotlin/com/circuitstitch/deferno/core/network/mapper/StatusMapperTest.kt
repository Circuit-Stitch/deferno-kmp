package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.DerivedChoreOccurrenceStatusWire
import com.circuitstitch.deferno.core.network.dto.OccurrenceStatusWire
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire-status → domain-state condensation (ADR-0011 "condense at the edge", CONTRACT-NOTES →
 * "Status"). Each branch of every mapper, incl. the `Unknown` fallbacks that keep an item visible
 * when the backend ships an additive status. The read/write asymmetry of occurrence actions is
 * also covered: the client only ever *writes* a coarse action, mapped to the kind-appropriate token.
 */
class StatusMapperTest {

    @Test
    fun taskStatusWireMapsToWorkingState() {
        assertEquals(WorkingState.Open, TaskStatusWire.Open.toWorkingState())
        assertEquals(WorkingState.InProgress, TaskStatusWire.InProgress.toWorkingState())
        assertEquals(WorkingState.InReview, TaskStatusWire.InReview.toWorkingState())
        assertEquals(WorkingState.Done, TaskStatusWire.Done.toWorkingState())
        assertEquals(WorkingState.Dropped, TaskStatusWire.Dropped.toWorkingState())
        // Unknown degrades to Open so an additively-statused Task stays visible.
        assertEquals(WorkingState.Open, TaskStatusWire.Unknown.toWorkingState())
    }

    @Test
    fun workingStateMapsToWireToken() {
        // The write direction (#23): exact hyphenated wire casing, inverse of toWorkingState.
        assertEquals("open", WorkingState.Open.toWireToken())
        assertEquals("in-progress", WorkingState.InProgress.toWireToken())
        assertEquals("in-review", WorkingState.InReview.toWireToken())
        assertEquals("done", WorkingState.Done.toWireToken())
        assertEquals("dropped", WorkingState.Dropped.toWireToken())
    }

    @Test
    fun defStatusWireMapsToDefinitionState() {
        assertEquals(DefinitionState.Active, DefStatusWire.Active.toDefinitionState())
        assertEquals(DefinitionState.InReview, DefStatusWire.InReview.toDefinitionState())
        assertEquals(DefinitionState.Archived, DefStatusWire.Archived.toDefinitionState())
        assertEquals(DefinitionState.Active, DefStatusWire.Unknown.toDefinitionState())
    }

    @Test
    fun occurrenceStatusWireMapsToOccurrenceState() {
        assertEquals(OccurrenceState.Scheduled, OccurrenceStatusWire.Scheduled.toOccurrenceState())
        assertEquals(OccurrenceState.InProgress, OccurrenceStatusWire.InProgress.toOccurrenceState())
        assertEquals(OccurrenceState.DoneOnTime, OccurrenceStatusWire.DoneOnTime.toOccurrenceState())
        assertEquals(OccurrenceState.DoneLate, OccurrenceStatusWire.DoneLate.toOccurrenceState())
        assertEquals(OccurrenceState.Skipped, OccurrenceStatusWire.Dropped.toOccurrenceState())
        assertEquals(OccurrenceState.Scheduled, OccurrenceStatusWire.Unknown.toOccurrenceState())
    }

    @Test
    fun derivedChoreOccurrenceStatusWireMapsToOccurrenceState() {
        assertEquals(OccurrenceState.Scheduled, DerivedChoreOccurrenceStatusWire.Scheduled.toOccurrenceState())
        assertEquals(OccurrenceState.Missed, DerivedChoreOccurrenceStatusWire.Missed.toOccurrenceState())
        assertEquals(OccurrenceState.InProgress, DerivedChoreOccurrenceStatusWire.InProgress.toOccurrenceState())
        assertEquals(OccurrenceState.DoneOnTime, DerivedChoreOccurrenceStatusWire.DoneOnTime.toOccurrenceState())
        assertEquals(OccurrenceState.DoneLate, DerivedChoreOccurrenceStatusWire.DoneLate.toOccurrenceState())
        assertEquals(OccurrenceState.Skipped, DerivedChoreOccurrenceStatusWire.Skipped.toOccurrenceState())
        assertEquals(OccurrenceState.Scheduled, DerivedChoreOccurrenceStatusWire.Unknown.toOccurrenceState())
    }

    @Test
    fun occurrenceActionEmitsKindAppropriateWireToken() {
        // Start/Complete are shared; Skip diverges by kind (chore `skipped` vs event `dropped`).
        assertEquals("in_progress", OccurrenceAction.Start.toWireToken(OccurrenceKind.Chore))
        assertEquals("in_progress", OccurrenceAction.Start.toWireToken(OccurrenceKind.Event))
        assertEquals("done", OccurrenceAction.Complete.toWireToken(OccurrenceKind.Chore))
        assertEquals("done", OccurrenceAction.Complete.toWireToken(OccurrenceKind.Event))
        assertEquals("skipped", OccurrenceAction.Skip.toWireToken(OccurrenceKind.Chore))
        assertEquals("dropped", OccurrenceAction.Skip.toWireToken(OccurrenceKind.Event))
    }
}
