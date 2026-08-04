package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.DerivedChoreOccurrenceStatusWire
import com.circuitstitch.deferno.core.network.dto.OccurrenceStatusWire
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wire-status → domain-state condensation (ADR-0011 "condense at the edge", CONTRACT-NOTES →
 * "Status"). Each branch of every mapper, incl. the `Unknown` fallbacks that keep an item visible
 * when the backend ships an additive status. The read/write asymmetry of occurrence actions is
 * also covered: the client only ever *writes* a coarse action, mapped to the kind-appropriate token.
 *
 * The occurrence family is covered on the **one** axis this module owns (ADR-0053 decision 4):
 * `toResolution`/`toResolutionOrNull`, the stored fact. The render-time reading has no mapper here by
 * design and is pinned by `core:model`'s `OccurrenceStateResolverTest` instead. What this file still
 * proves about the split is the load-bearing half — the fact mapper refuses to condense the chore
 * endpoint's two *derived* arms at all.
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
    fun definitionStateMapsToWireToken() {
        // The write direction (#299): exact wire casing, inverse of toDefinitionState.
        assertEquals("active", DefinitionState.Active.toWireToken())
        assertEquals("in-review", DefinitionState.InReview.toWireToken())
        assertEquals("archived", DefinitionState.Archived.toWireToken())
    }

    @Test
    fun definitionStateWireTokenRoundTripsWithTheReadMapper() {
        // The set-then-read invariant (#299): every DefinitionState's wire token must decode back through
        // the actual wire enum (DefStatusWire via DefernoJson) to the SAME state — so an optimistic
        // SetDefinitionState and the server snapshot agree. Decodes the emitted token as the real wire enum.
        for (state in DefinitionState.entries) {
            val token = state.toWireToken()
            val wire = DefernoJson.decodeFromString(DefStatusWire.serializer(), "\"$token\"")
            assertEquals(state, wire.toDefinitionState(), "round-trip of $state via wire token $token")
        }
    }

    // The two wire → OccurrenceState cases that used to sit here were removed with the mappers
    // themselves (#390). Nothing in the module can produce a reading from a wire token any more, so
    // there is nothing to assert: the corresponding contract now lives in `core:model`'s
    // OccurrenceStateResolverTest, which pins the reading against facts, coverage and today — the three
    // inputs a wire token does not carry and which a mapper here could only have guessed at.

    @Test
    fun occurrenceStatusWireMapsToAStoredResolutionAndIsTotal() {
        // The FACT axis (#390, ADR-0053 decision 4), not the reading axis above. Total and never null:
        // GET /events/{id}/occurrences returns only stored rows, so `scheduled` there is a written row
        // recording no progress — a fact — and not the derived "nothing has happened yet".
        assertEquals(OccurrenceResolution.Scheduled, OccurrenceStatusWire.Scheduled.toResolution())
        assertEquals(OccurrenceResolution.InProgress, OccurrenceStatusWire.InProgress.toResolution())
        assertEquals(OccurrenceResolution.DoneOnTime, OccurrenceStatusWire.DoneOnTime.toResolution())
        assertEquals(OccurrenceResolution.DoneLate, OccurrenceStatusWire.DoneLate.toResolution())
        // The event spelling of the one domain terminal the chore endpoint spells `skipped`.
        assertEquals(OccurrenceResolution.Skipped, OccurrenceStatusWire.Dropped.toResolution())
        // An additive token degrades to a Scheduled FACT: the row exists, we just cannot read its
        // status, and recording that keeps the date from later being derived as Missed out of ignorance.
        assertEquals(OccurrenceResolution.Scheduled, OccurrenceStatusWire.Unknown.toResolution())
    }

    @Test
    fun derivedChoreScheduledAndMissedAreServerOpinionsAndYieldNoFact() {
        // These two arms are the server's reading about *today*, taken against Utc::now()
        // (occurrences.rs:465/:488-495) — `scheduled` means no record exists at all. Neither is a
        // fact, so neither may be stored: the mapper returns null and the caller writes no row.
        assertNull(DerivedChoreOccurrenceStatusWire.Scheduled.toResolutionOrNull())
        assertNull(DerivedChoreOccurrenceStatusWire.Missed.toResolutionOrNull())
    }

    @Test
    fun derivedChoreStoredArmsMapToResolutionsOneToOne() {
        assertEquals(OccurrenceResolution.InProgress, DerivedChoreOccurrenceStatusWire.InProgress.toResolutionOrNull())
        assertEquals(OccurrenceResolution.DoneOnTime, DerivedChoreOccurrenceStatusWire.DoneOnTime.toResolutionOrNull())
        assertEquals(OccurrenceResolution.DoneLate, DerivedChoreOccurrenceStatusWire.DoneLate.toResolutionOrNull())
        // The chore endpoint spells the terminal `skipped` and never `dropped`.
        assertEquals(OccurrenceResolution.Skipped, DerivedChoreOccurrenceStatusWire.Skipped.toResolutionOrNull())
        // Unknown degrades to a Scheduled FACT rather than to null — null here would let a date the
        // server does hold a row for be derived as Missed purely because we could not read its token.
        assertEquals(OccurrenceResolution.Scheduled, DerivedChoreOccurrenceStatusWire.Unknown.toResolutionOrNull())
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
