package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.plugin.Anchor
import com.circuitstitch.deferno.core.model.plugin.Dynamics
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import com.circuitstitch.deferno.core.model.plugin.PersistencePolicy
import com.circuitstitch.deferno.core.model.resolveOccurrenceState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ratified targets (#420), asserted rather than reviewed.
 *
 * [BehaviourParityTest] pins that the *parity* recipe changes nothing. This pins the other half: that
 * each target which claims to match a shipped reading actually does, and that each target which
 * deliberately departs from parity departs in the direction that was ratified. A target recorded only
 * in KDoc drifts from the reading it was justified by, and nothing fails.
 */
class TargetRecipeTest {

    // ── Persistence ────────────────────────────────────────────────────────────────────────────

    @Test
    fun theHorizonPolicyAgreesWithWhatTheOccurrenceReadingAlreadyRenders() {
        // The whole justification for reseeding off the light switch: `resolveOccurrenceState` is the
        // shipped answer for a past unresolved firing, it has no kind branch, and it splits on the
        // definition state. So the target policy has to agree with it arm for arm, or the model is
        // back to contradicting the interface — which is the defect this target exists to fix.
        val past = LocalDate(2026, 3, 1)
        val today = LocalDate(2026, 3, 8)

        for (state in DefinitionState.entries) {
            val rendered = resolveOccurrenceState(
                fact = null,
                covered = true,
                definitionState = state,
                date = past,
                today = today,
            )
            val policy = TargetRecipe.persistenceAtHorizon(Lifecycle.Definition(state))
            val agrees = when (rendered) {
                // The miss is a reproach the surface shows, so the policy must be the one that logs it.
                OccurrenceState.Missed -> policy == PersistencePolicy.SkippedIfMissed
                // History, not a reproach: nothing is recorded against the person.
                OccurrenceState.Skipped -> policy == PersistencePolicy.ExpiresAfterWindow
                else -> false
            }
            assertTrue(agrees, "$state renders $rendered but the target policy is $policy")
        }
    }

    @Test
    fun aLiveDefinitionLogsTheMissAndAShelvedOneDoesNot() {
        // Stated as literals as well, deliberately not re-derived from the reading above: a rule
        // inverted in both places would otherwise agree with itself. This is the arm ADR-0056 called
        // the trap, ratified as the parity answer instead — see the ADR's own amendment.
        assertEquals(
            PersistencePolicy.SkippedIfMissed,
            TargetRecipe.persistenceAtHorizon(Lifecycle.Definition(DefinitionState.Active)),
        )
        assertEquals(
            PersistencePolicy.ExpiresAfterWindow,
            TargetRecipe.persistenceAtHorizon(Lifecycle.Definition(DefinitionState.Archived)),
        )
        assertEquals(
            PersistencePolicy.ExpiresAfterWindow,
            TargetRecipe.persistenceAtHorizon(Lifecycle.Definition(DefinitionState.InReview)),
        )
    }

    @Test
    fun aTaskStillRollsForwardAndTheAnswerNoLongerComesFromItsKind() {
        // A Task's past deadline stays on the plan and reads overdue; that half was never in doubt.
        // What changed is where the answer comes from — every WorkingState gives the same policy, so
        // nothing here is reading the kind back in through a lifecycle-shaped side door.
        for (state in WorkingState.entries) {
            assertEquals(
                PersistencePolicy.UntilComplete,
                TargetRecipe.persistenceAtHorizon(Lifecycle.Working(state)),
                "$state must not vary the horizon policy",
            )
        }
        assertEquals(PersistencePolicy.UntilComplete, TargetRecipe.persistenceAtHorizon(Lifecycle.Unstated))
    }

    @Test
    fun theTargetDivergesFromTheParitySeedOnExactlyTheLapsingKinds() {
        // The named user-visible delta, pinned so it cannot widen unnoticed. Task and Chore are
        // unchanged; the two kinds the seed called "lapses, unrecorded" become "the miss is logged",
        // which is what their rows already render.
        val parity = ItemKind.entries.associateWith { PersistenceSeed.of(it) }
        val target = mapOf(
            ItemKind.Task to TargetRecipe.persistenceAtHorizon(Lifecycle.Working(WorkingState.Open)),
            ItemKind.Chore to TargetRecipe.persistenceAtHorizon(Lifecycle.Definition(DefinitionState.Active)),
            ItemKind.Habit to TargetRecipe.persistenceAtHorizon(Lifecycle.Definition(DefinitionState.Active)),
            ItemKind.Event to TargetRecipe.persistenceAtHorizon(Lifecycle.Definition(DefinitionState.Active)),
        )
        assertEquals(
            setOf(ItemKind.Chore, ItemKind.Habit, ItemKind.Event),
            ItemKind.entries.filter { parity[it] != target[it] }.toSet(),
            "the ratified divergence from parity moved",
        )
    }

    @Test
    fun everyPolicyIsReachableExceptTheOneThatWouldMintAWireRowFromADeviceLocalValue() {
        assertTrue(TargetRecipe.isReachable(PersistencePolicy.UntilComplete))
        assertTrue(TargetRecipe.isReachable(PersistencePolicy.ExpiresAfterWindow))
        assertTrue(TargetRecipe.isReachable(PersistencePolicy.SkippedIfMissed))
        assertTrue(TargetRecipe.isReachable(PersistencePolicy.DegradesIntoState("passport expired")))
        assertFalse(
            TargetRecipe.isReachable(PersistencePolicy.CreatesFollowUp("reschedule appointment")),
            "CreatesFollowUp is deferred — it would enqueue a row for a policy that never syncs",
        )
    }

    // ── Unfolding ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun noKindSeedsABoundSoEveryItemStaysUnderspecified() {
        // "Underspecified, not defaulted" is Dynamics' own rule, and the seed honours it. Asserted
        // against the member rather than against an aspect, because the aspect of an item with a rule
        // is Habitual either way and would hide a seed that had crept in.
        assertEquals(Dynamics.Unstated, TargetRecipe.BOUND_SEED)
    }

    // ── Temporal ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aSurfaceRendersTheStoredFlagAndTheDerivedReadingStaysAvailableBesideIt() {
        // The disagreeing row the decision is about: the flag claims all-day and there are clock times
        // beside it. The flag wins for rendering, and the derived reading still says what it always did.
        val disagreeing = Anchor.Appointment(
            startTimeOfDay = kotlinx.datetime.LocalTime(9, 0),
            allDayFlag = true,
        )
        assertTrue(TargetRecipe.allDayAsRendered(disagreeing), "a surface renders the stored flag")
        assertFalse(disagreeing.isAllDay, "the derived reading is unchanged and still disagrees")
        assertTrue(TargetRecipe.clockTimesAreCruft(disagreeing), "the times are what needs clearing")
    }

    @Test
    fun theUncleanableDirectionIsNotReportedAsCruft() {
        // Flag false, no clock times. The two disagree, but nothing says what the times should have
        // been, so this can only be asked — never repaired by a sweep. Reporting it as cruft would
        // invite a cleanup that destroys the only claim the row makes.
        val unrepairable = Anchor.Appointment(allDayFlag = false)
        assertTrue(unrepairable.isAllDay, "no clock times, so the derived reading is all-day")
        assertFalse(TargetRecipe.allDayAsRendered(unrepairable), "the stored flag still wins")
        assertFalse(TargetRecipe.clockTimesAreCruft(unrepairable), "there are no times to clear")
    }

    @Test
    fun anAgreeingAppointmentIsNeverFlaggedForCleanup() {
        val allDay = Anchor.Appointment(allDayFlag = true)
        val timed = Anchor.Appointment(startTimeOfDay = kotlinx.datetime.LocalTime(17, 0))
        assertFalse(TargetRecipe.clockTimesAreCruft(allDay))
        assertFalse(TargetRecipe.clockTimesAreCruft(timed))
    }

    // ── Enactment ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun theLifecycleRecutKeepsTheLightSwitchAndMovesThePerDoingClaimsOff() {
        // Open and InProgress and Done all land on Active: none of the three says the item's own life
        // has ended, and the two that describe a doing move to the record that owns a doing.
        assertEquals(DefinitionState.Active, TargetRecipe.definitionLifecycleFor(WorkingState.Open))
        assertEquals(DefinitionState.Active, TargetRecipe.definitionLifecycleFor(WorkingState.InProgress))
        assertEquals(DefinitionState.Active, TargetRecipe.definitionLifecycleFor(WorkingState.Done))
        assertEquals(DefinitionState.InReview, TargetRecipe.definitionLifecycleFor(WorkingState.InReview))
        assertEquals(DefinitionState.Archived, TargetRecipe.definitionLifecycleFor(WorkingState.Dropped))
    }

    @Test
    fun theRecutIsTotalOverTodaysWorkingStates() {
        // A member added to WorkingState must be answered for rather than inheriting someone else's
        // arm — the same reason PersistenceSeed is exhaustive over ItemKind.
        for (state in WorkingState.entries) {
            TargetRecipe.definitionLifecycleFor(state)
        }
    }

    @Test
    fun droppedAndArchivedCollapseAndThatIsTheRatifiedCost() {
        // The named user-visible change, pinned as an assertion so it is impossible to ship the re-cut
        // while believing the two claims survived it.
        assertEquals(
            TargetRecipe.definitionLifecycleFor(WorkingState.Dropped),
            DefinitionState.Archived,
            "a dropped Task and an archived definition become one claim",
        )
    }

    @Test
    fun terminalityStopsBeingReadableOffTheDefinitionAlone() {
        // The consequence of moving Done to the firing: a finished Task's definition reads Active, so
        // a surface that filtered on a terminal *definition* state would show it as live. Terminality
        // becomes a reading over the light switch plus the firings, which is the move OccurrenceState
        // already makes. Pinned because it is the sharpest edge in the re-cut.
        val finished = TargetRecipe.definitionLifecycleFor(WorkingState.Done)
        assertEquals(DefinitionState.Active, finished)
        assertFalse(
            Lifecycle.Definition(finished).isTerminal,
            "a finished item's definition is not terminal once doneness lives on the firing",
        )
        assertTrue(
            Lifecycle.Working(WorkingState.Done).isTerminal,
            "today's reading is the contrast — this is what changes",
        )
    }
}
