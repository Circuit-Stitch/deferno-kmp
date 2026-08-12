package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.Cadence
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The four readings derived from a plugin list — aspect, lapse, attainment and drive — and the rule
 * they all obey: **store the evidence, derive the label.**
 *
 * That rule is not new here. `OccurrenceState` is already documented as a reading and never a stored
 * value, and offline-first already requires caching inputs and recomputing rather than caching a
 * server-derived answer. What is new is that four more labels now follow it, and none of them has a
 * field anywhere to be stored in.
 */
class ReadingsTest {

    private val core = Core(
        id = "11111111-0000-4000-8000-000000000001",
        orgSlug = "u-e4h2qk",
        title = "Renew the passport",
        dateCreated = Instant.parse("2026-01-05T08:00:00Z"),
    )

    private fun item(vararg plugins: Plugin) = Item(core, plugins.toList())
    private fun occurrence(vararg plugins: Plugin) =
        Occurrence(core.id, LocalDate(2026, 3, 1), plugins.toList())

    // ── Aspect ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyBoundProducesItsAspectAndAbsenceProducesTheTopOfTheLattice() {
        assertEquals(Aspect.Process, item().aspect(), "no bound loaded must be underspecified, not defaulted")
        assertEquals(Aspect.AtelicProcess, item(Dynamics.NoFinishLine).aspect())
        assertEquals(Aspect.Activity, item(Dynamics.Unbounded).aspect())
        assertEquals(Aspect.State, item(Dynamics.Maintained("inbox below 20")).aspect())
        assertEquals(Aspect.Endeavor, item(Dynamics.Timeboxed(20)).aspect())
        assertEquals(Aspect.Performance, item(Dynamics.Telic("passport in hand")).aspect())
    }

    @Test
    fun recurrenceSetsTheDefinitionsAspectAndTheBoundIsReadBesideIt() {
        // "Take the bins out weekly" — recurrence and telos are orthogonal, and both are carried on
        // the definition without either overwriting the other. A rule wins the *definition's* aspect;
        // the bound is still there to be read.
        val weekly = Repeats(Recurrence(Cadence.Daily))
        val telic = Dynamics.Telic("bins are out")
        val definition = item(weekly, telic)

        assertEquals(emptyList(), definition.validate(), "a rule and a bound coexist on a definition")
        assertEquals(Aspect.Habitual, definition.aspect())
        assertEquals(telic, definition.dynamics, "the bound survives beside the rule")
    }

    @Test
    fun aDoingCannotCarryItsOwnBoundYetSoItsAspectIsTheTopOfTheLattice() {
        // The two-level reading — "a Habitual item whose every doing is a Performance" — is NOT
        // reachable today, and this pins that rather than letting the gap read as a passing assertion.
        // `Dynamics.scope` is `Scope.Definition`, so `Occurrence.validate()` rejects a bound outright
        // and `Occurrence.aspect()` can only ever return the degenerate answer. The per-date override
        // channel `Placement.kt` anticipates is what closes this; until it lands, an occurrence-scoped
        // bound is an invalid record, not a supported shape (#420).
        val telic = Dynamics.Telic("bins are out")

        assertTrue(
            occurrence(telic).validate().any { it.contains("Telic") },
            "a definition-scoped bound on an Occurrence must be reported as misplaced",
        )
        assertEquals(
            Aspect.Process,
            occurrence().aspect(),
            "with no legal way to carry a bound, every doing reads the top of the lattice",
        )
    }

    @Test
    fun aspectIsAPluginSwapWithinOneFamilyAndCostsNothingElse() {
        // The swap-without-loss property the re-cut exists for, in one assertion: converting
        // "practice scales for 20 minutes" into "practice scales, no endpoint" moves one Family and
        // leaves content and modality exactly where they were.
        val before = item(
            Prioritizable(pinned = true),
            Volition(0.9),
            Dynamics.Timeboxed(20),
        )
        val after = before.copy(plugins = before.plugins.replacingFamilyOf(Dynamics.Unbounded))

        assertEquals(Aspect.Endeavor, before.aspect())
        assertEquals(Aspect.Activity, after.aspect())
        assertEquals(before.priority, after.priority, "content moved during an aspect change")
        assertEquals(before.volition, after.volition, "modality moved during an aspect change")
    }

    @Test
    fun narrowingGoesDownTheLatticeAndNeverSidewaysOrUp() {
        // The ingestion contract: a reader may emit any node and a later pass may only narrow it.
        // Nothing is retracted, which is what makes an underspecified answer better than a guess.
        assertTrue(Dynamics.Unbounded.narrows(Dynamics.NoFinishLine), "Activity sits inside AtelicProcess")
        assertTrue(Dynamics.Maintained("x").narrows(Dynamics.NoFinishLine), "State sits inside AtelicProcess")
        assertTrue(Dynamics.Telic("x").narrows(Dynamics.Unstated), "everything narrows the top")
        assertFalse(Dynamics.NoFinishLine.narrows(Dynamics.Unbounded), "you may not un-learn")
        assertFalse(Dynamics.Maintained("x").narrows(Dynamics.Unbounded), "State and Activity are siblings")
        assertTrue(Dynamics.Unstated.narrows(Dynamics.Unstated), "nothing narrows only itself")
    }

    // ── Lapse ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyPolicyProducesItsLapseAndAbsenceRollsForward() {
        // Unlike the bound, absence here is a DEFAULT and asserts something: an unresolved doing
        // rolls forward. That is what a Task and a Chore do today.
        assertEquals(Lapse.Persists, item().atHorizon())
        assertEquals(Lapse.Vanishes, item(PersistencePolicy.ExpiresAfterWindow).atHorizon())
        assertEquals(Lapse.LoggedMissed, item(PersistencePolicy.SkippedIfMissed).atHorizon())
        assertEquals(
            Lapse.BecomesState("passport expired"),
            item(PersistencePolicy.DegradesIntoState("passport expired")).atHorizon(),
        )
        assertEquals(
            Lapse.Spawns("reschedule appointment"),
            item(PersistencePolicy.CreatesFollowUp("reschedule appointment")).atHorizon(),
        )
    }

    // ── Attainment ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theDefinitionIsAskedBeforeTheDateSoAStrayVerdictChangesNothing() {
        // "Did the goal obtain?" is only a question where there is a goal. A timebox answers
        // NothingToAttain whatever the date's record says — which is what collapses a five-branch
        // guess into a read.
        val timeboxed = item(Dynamics.Timeboxed(20))
        assertEquals(
            Attainment.NothingToAttain,
            satisfied(timeboxed, occurrence(Evaluation(obtained = false))),
        )
        // It does not make the pair legal — that is `verdictProblems`' job, and it says so.
        assertTrue(verdictProblems(timeboxed.dynamics, Evaluation(obtained = false)).isNotEmpty())
    }

    @Test
    fun onlyThePairCanCatchAVerdictWithNoCriterionToBeAbout() {
        // Each record is faultless read alone — a timebox is a legal bound and a verdict is a legal
        // thing to record — so `problemsAcross` is the only place the pairing can be rejected.
        val timeboxed = item(Dynamics.Timeboxed(20))
        val verdict = occurrence(Evaluation(obtained = true))
        assertEquals(emptyList(), timeboxed.validate())
        assertEquals(emptyList(), verdict.validate())

        val problems = problemsAcross(timeboxed, verdict)
        assertEquals(1, problems.size, "expected exactly one cross-record problem, got $problems")
        assertTrue(problems.single().contains("criterion"), problems.single())

        assertEquals(emptyList(), problemsAcross(item(Dynamics.Telic("passed the test")), verdict))
    }

    @Test
    fun stoppingAndAttainingAreDifferentClaims() {
        // Sit the driving test, fail it, record that you left at 11am: a reader consulting only the
        // finish timestamp hands you a licence. The verdict is what separates the two.
        val telic = item(Dynamics.Telic("passed the test"))
        assertEquals(Attainment.Obtained("passed the test"), satisfied(telic, occurrence(Evaluation(true))))
        assertEquals(Attainment.Failed("passed the test"), satisfied(telic, occurrence(Evaluation(false))))
        assertIs<Attainment.Unevaluated>(satisfied(telic, occurrence()))
    }

    @Test
    fun noBoundLoadedIsUndeterminedRatherThanNothingToAttain() {
        // The counterpart of Aspect.Process: a reader that has not decided has not thereby claimed
        // the thing has no endpoint.
        assertEquals(Attainment.Undetermined, satisfied(item(), occurrence()))
    }

    @Test
    fun aConditionMayNotHaveDatesGeneratedAheadOfIt() {
        // A queue of completable rows for something that is never completed is the permanently-open
        // Task the bound axis exists to stop faking.
        assertTrue(unfoldingProblems(Dynamics.Maintained("inbox below 20"), repeats = true).isNotEmpty())
        assertTrue(unfoldingProblems(Dynamics.Maintained("inbox below 20"), repeats = false).isEmpty())
        assertTrue(unfoldingProblems(Dynamics.Telic("x"), repeats = true).isEmpty())
    }

    // ── Drive ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun driveReadsUnstatedForEveryItemUntilTheShadowStoreLands() {
        // The honest answer today, and the acceptance criterion #419 states outright: carrots do not
        // exist until something persists them, so nothing says what any item is for.
        assertEquals(Drive.Unstated, item().drive { null })
        assertFalse(item().drive { null }.isDropCandidate, "an unanswered question is not evidence")
    }

    @Test
    fun driveReadsTheChainAndNotTheItemsOwnModality() {
        // Drive is asked exactly when this item's own modality is weak, so answering with it answers
        // nothing. The strong want below is on the CARROT, and the item itself dreads the task.
        val japan = Item(core.copy(id = "carrot-1", title = "Go to Japan"), listOf(Volition(0.9)))
        val passport = item(Volition(0.0), Purpose(listOf(Carrot.Linked("carrot-1"))))

        val drive = assertIs<Drive.From>(passport.drive { id -> japan.takeIf { id == "carrot-1" } })
        assertEquals(Strength.Strong, drive.wanted?.strength)
        assertEquals("Go to Japan", drive.wanted?.toward)
        assertFalse(drive.isDropCandidate)
    }

    @Test
    fun aWantAndAnObligationAreTwoAnswersAndAreNeverMerged() {
        // Ranking a want against a must is the collapse that loses the dreaded must — you want the
        // open mic and are obliged to the recital, and a surface has to be able to say both.
        val openMic = Item(core.copy(id = "a", title = "Open mic"), listOf(Volition(0.9)))
        val recital = Item(core.copy(id = "b", title = "Recital"), listOf(Obligation(Force.Must)))
        val practise = item(Purpose(listOf(Carrot.Linked("a"), Carrot.Linked("b"))))

        val drive = assertIs<Drive.From>(
            practise.drive { id -> listOf(openMic, recital).firstOrNull { it.core.id == id } },
        )
        assertEquals("Open mic", drive.wanted?.toward)
        assertEquals("Recital", drive.required?.toward)
        assertEquals(Force.Must, drive.required?.force)
    }

    @Test
    fun carrotsWithNothingSaidAboutThemAreUnweighedRatherThanEmpty() {
        // A prompt, not a verdict. Nobody was asked whether they want this, and an unanswered
        // question is not a reason to drop anything.
        val japan = Item(core.copy(id = "carrot-1", title = "Go to Japan"))
        val passport = item(Purpose(listOf(Carrot.Linked("carrot-1"))))

        val drive = assertIs<Drive.Unweighed>(passport.drive { id -> japan.takeIf { id == "carrot-1" } })
        assertEquals(1, drive.carrots.size)
        assertFalse(drive.isDropCandidate)
    }

    @Test
    fun aLookThatCameBackEmptyIsTheOnlyDropCandidate() {
        // Weak still keeps an item — thin is a reason to go looking for a better carrot, not to drop.
        // A permission does not keep one: a thing you are merely allowed to do is no reason to push
        // past anything.
        val nobodyWants = Item(core.copy(id = "a", title = "Nothing"), listOf(Volition(0.0)))
        val merelyAllowed = Item(core.copy(id = "b", title = "Optional"), listOf(Obligation(Force.May)))
        val thin = Item(core.copy(id = "c", title = "Thin"), listOf(Volition(0.3)))
        val all = listOf(nobodyWants, merelyAllowed, thin)

        fun driveOver(vararg ids: String) =
            item(Purpose(ids.map { Carrot.Linked(it) })).drive { id -> all.firstOrNull { it.core.id == id } }

        assertTrue(driveOver("a", "b").isDropCandidate, "no want and only a permission is a candidate")
        assertFalse(driveOver("c").isDropCandidate, "thin is a reason to look harder, not to drop")
    }

    @Test
    fun aCycleInThePurposeChainTerminates() {
        // Two items pointing at each other is representable and must not hang the reading.
        val a = Item(core.copy(id = "a", title = "A"), listOf(Purpose(listOf(Carrot.Linked("b")))))
        val b = Item(core.copy(id = "b", title = "B"), listOf(Purpose(listOf(Carrot.Linked("a")))))
        val drive = a.drive { id -> listOf(a, b).firstOrNull { it.core.id == id } }
        assertIs<Drive.Unweighed>(drive)
    }

    @Test
    fun wordsAreACarrotWithoutAnItemBehindThem() {
        // "Go to Japan" need not be a task to be the reason you renew the passport, and becoming a
        // linked item later takes nothing back.
        val drive = assertIs<Drive.Unweighed>(
            item(Purpose(listOf(Carrot.InWords("go to Japan")))).drive { null },
        )
        assertEquals(listOf(Carrot.InWords("go to Japan")), drive.carrots)
        assertTrue(Purpose(listOf(Carrot.InWords(" "))).validate().isNotEmpty(), "a blank carrot is invalid")
    }
}
