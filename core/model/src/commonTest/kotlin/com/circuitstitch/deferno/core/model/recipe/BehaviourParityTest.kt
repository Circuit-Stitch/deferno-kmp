package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.CadenceMode
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Lapse
import com.circuitstitch.deferno.core.model.plugin.PersistencePolicy
import com.circuitstitch.deferno.core.model.plugin.Strength
import com.circuitstitch.deferno.core.model.plugin.atHorizon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ADR-0056 **behaviour-parity gate**: what a reader derives from a plugin set equals what ships
 * today, asserted rather than reviewed.
 *
 * The round-trip gate proves nothing is *lost*. This one proves nothing is quietly *changed* — a
 * recipe can preserve every field and still hand a reader a different answer, which is the failure
 * mode a refactor disguised as a re-model has.
 *
 * ### The four readings, and where each lives today
 *
 * Named here because finding them cost more than writing them down, and because two of the four are
 * not where ADR-0056's shorthand implies:
 *
 *  - **Lateness.** Not in `NextDeadline.kt` — that is Chore-only rolling-cadence advancement and
 *    computes no lateness at all. It is `completionResolution(doneAt, completeBy)` in
 *    `OccurrenceFact.kt`, branched per kind in `core:data`'s occurrence mutation, where an Event
 *    hard-codes `DoneOnTime` because the server rejects a late Event outright. The plugin-side
 *    answer is `Anchor.latenessIsMeaningful`, and `TemporalConflationTest` asserts the two agree
 *    over the whole corpus.
 *  - **The temporal anchor.** An Event's `completeBy` is a **start**; the same field on the other
 *    three is a **deadline**, with no conversion. Reproduced faithfully, and named as deliberate in
 *    `TemporalConflationTest` rather than left to be discovered.
 *  - **Terminality.** `Item.isTerminal` on the shipped cross-kind projection: a Done or Dropped
 *    Task, or an Archived definition. Two lifecycles, one de-emphasis rule, restated at each call
 *    site today and derived once from `Lifecycle` here.
 *  - **Lapse (`carriesForward`).** *There is no `carriesForward` function in this client.* The only
 *    thing bearing the name is `IfMissed.CarriesForward` in `core:domain`'s `CaptureInput` — a
 *    capture-time input that **derives the kind** (carries-forward → Chore, lapses → Habit), not a
 *    reading over an existing row. So the parity claim cannot be "reproduce `carriesForward`"; it is
 *    "reproduce the one-bit answer the kind stands in for", which the epic states as Task and Chore
 *    roll forward while Habit and Event do not. That bit is **unwritten today** and `PersistenceSeed`
 *    is the first place this codebase says it out loud. With nothing to diff against, what is
 *    asserted instead is that the seed is derivable from that one bit alone and that the three richer
 *    policies stay unreachable — which is what "no behaviour changes" means when the shipped answer
 *    exists only in the shape of the kind vocabulary.
 *
 * `resolveOccurrenceState` is the fifth reading ADR-0056 names, and it is per **firing**: its inputs
 * are a fact, coverage, the definition state, a date and today. Its plugin-side counterpart is the
 * Occurrence-scoped half of Enactment, which lands with the Occurrence corpus. What *this* corpus
 * can and does assert is that the one input it supplies — the definition's own lifecycle — survives
 * intact, since a `Missed`-vs-`Skipped` split turns on it.
 */
class BehaviourParityTest {

    @Test
    fun terminalityReadsTheSameOffPluginsAsItDoesOffTheKindRow() {
        // `Item.isTerminal` today: a Done/Dropped Task, or an Archived definition. Two lifecycles
        // and one rule, restated wherever a row is de-emphasized. Asserted over every shape rather
        // than one of each, so a lifecycle value added to either enum has to be answered for.
        for (shape in KindShapes.ALL) {
            val shipped = when (shape) {
                is KindShape.OfTask -> shape.task.workingState.isTerminal
                is KindShape.OfHabit -> shape.habit.definitionState == DefinitionState.Archived
                is KindShape.OfChore -> shape.chore.definitionState == DefinitionState.Archived
                is KindShape.OfEvent -> shape.event.definitionState == DefinitionState.Archived
            }
            assertEquals(
                shipped,
                readOf(shape).progress.lifecycle.isTerminal,
                "${shape.label}: terminality disagrees with what ships today",
            )
        }
    }

    @Test
    fun theDefinitionLifecycleTheOccurrenceReadingTurnsOnSurvivesIntact() {
        // `resolveOccurrenceState` reads the definition state to split a past unresolved firing into
        // Missed (Active) or Skipped (InReview / Archived), and returns Unknown when it is null. So
        // the exact three-valued answer has to survive the recipe, not merely a terminal/not bit.
        for (shape in KindShapes.ALL.filter { it.kind != ItemKind.Task }) {
            val shipped = when (shape) {
                is KindShape.OfHabit -> shape.habit.definitionState
                is KindShape.OfChore -> shape.chore.definitionState
                is KindShape.OfEvent -> shape.event.definitionState
                is KindShape.OfTask -> error("filtered out")
            }
            val plugin = readOf(shape).progress.lifecycle
            assertEquals(
                com.circuitstitch.deferno.core.model.plugin.Lifecycle.Definition(shipped),
                plugin,
                "${shape.label}: the definition state a firing's reading turns on did not survive",
            )
        }
    }

    @Test
    fun theCadenceAdvancementInputsSurviveExactly() {
        // `nextDeadlineAfter(cadenceMode, recurrence, doneAt, zone)` is Chore-only and takes two of
        // its four arguments off the row. Both are in one plugin now, and both have to arrive
        // unchanged or a rolling chore would advance to a different day — including the `Unmodelled`
        // token, which is a real wire value and must not collapse into Rolling.
        for (shape in KindShapes.ALL.filterIsInstance<KindShape.OfChore>()) {
            val repeats = ParityRecipe.read(shape.chore).repeats
            assertEquals(shape.chore.cadenceMode, repeats.cadenceMode, "${shape.label}: cadence mode moved")
            assertEquals(shape.chore.recurrence, repeats.recurrence, "${shape.label}: the rule moved")
        }
        // And the three kinds with no such field never acquire one — a Habit that started advancing
        // on a rolling cadence would be a behaviour change nobody asked for.
        for (shape in KindShapes.ALL.filter { it.kind == ItemKind.Habit || it.kind == ItemKind.Event }) {
            assertEquals(
                null,
                readOf(shape).repeats.cadenceMode,
                "${shape.label} acquired a cadence mode it has no wire field for",
            )
        }
    }

    @Test
    fun theOfflineExpansionInputsAreWrappedAndNotRestated() {
        // ADR-0053's non-negotiable: `Repeats` holds the shipped `Recurrence` and `SeriesInputs` by
        // reference. Identity of the *same objects* is the assertion, because a plugin that rebuilt
        // an equal-looking cadence would be a second definition of "every other Tuesday" for the
        // generated corpus to disagree with.
        for (shape in KindShapes.ALL.filter { it.kind != ItemKind.Task }) {
            val (rule, series, seriesId) = when (shape) {
                is KindShape.OfHabit -> Triple(shape.habit.recurrence, shape.habit.series, shape.habit.seriesId)
                is KindShape.OfChore -> Triple(shape.chore.recurrence, shape.chore.series, shape.chore.seriesId)
                is KindShape.OfEvent -> Triple(shape.event.recurrence, shape.event.series, shape.event.seriesId)
                is KindShape.OfTask -> error("filtered out")
            }
            val repeats = readOf(shape).repeats
            assertEquals(rule, repeats.recurrence, "${shape.label}: the rule was rebuilt rather than wrapped")
            assertEquals(series, repeats.series, "${shape.label}: the expansion inputs were rebuilt")
            assertEquals(seriesId, repeats.seriesId, "${shape.label}: the series id moved")
            // The elision must stay an elision. `series == null` means "this device cannot reproduce
            // that grid", which is not an empty grid, and `isExpandable` is the reading that keeps
            // the two apart.
            assertEquals(
                rule != null && series != null,
                repeats.isExpandable,
                "${shape.label}: the wire's elision was read as an empty grid",
            )
        }
    }

    @Test
    fun desireSurvivesAsAContinuousValueAndTheThreeValuedReadingIsDerived() {
        // ADR-0041's Backup file round trip carries `Task.desire` as a `Double?` through
        // `ItemView.Task` and back into `CreateTaskPayload`. Bucketing it to the reference model's
        // three values would be lossy AND would change what an exported-then-imported item holds, so
        // the plugin carries the Double and derives the buckets.
        for (shape in KindShapes.ALL.filterIsInstance<KindShape.OfTask>()) {
            assertEquals(
                shape.task.desire,
                ParityRecipe.read(shape.task).volition.desire,
                "${shape.label}: desire was bucketed on the way in",
            )
        }
        // The three kinds with no `desire` on the wire never acquire one — the degenerate read is
        // "nobody was asked", which is not "no".
        for (shape in KindShapes.ALL.filter { it.kind != ItemKind.Task }) {
            assertEquals(null, readOf(shape).volition.desire, "${shape.label} acquired a desire")
            assertEquals(Strength.Unstated, readOf(shape).volition.strength, shape.label)
        }
    }

    @Test
    fun theDerivedReadingsOverAPluginAnswerTheSameQuestionTheKindRowDid() {
        // The small readers each Family carries, checked against the shipped predicate they replace,
        // so none of them drifts into a differently-shaped answer under the same name.
        for (shape in KindShapes.ALL.filterIsInstance<KindShape.OfTask>()) {
            assertEquals(
                shape.task.hasAttachment,
                ParityRecipe.read(shape.task).attachable.hasAttachment,
                "${shape.label}: hasAttachment disagrees with Task.hasAttachment",
            )
        }
        for (shape in KindShapes.ALL) {
            val shipped = when (shape) {
                is KindShape.OfTask -> false
                is KindShape.OfHabit -> shape.habit.recurrence != null
                is KindShape.OfChore -> shape.chore.recurrence != null
                is KindShape.OfEvent -> shape.event.recurrence != null
            }
            assertEquals(shipped, readOf(shape).repeats.hasRule, "${shape.label}: hasRule disagrees with the row")
        }
    }

    @Test
    fun theThreeValuedDesireReadingIsDerivedAndKeepsZeroApartFromUnstated() {
        // The reading a surface would ask for if it wanted the reference model's three buckets. The
        // fourth member is the one that matters: `null` is "nobody was asked" and `0.0` is "asked,
        // and no", and collapsing them is what makes an unanswered question read as evidence.
        val task = KindShapes.ALL.filterIsInstance<KindShape.OfTask>().first().task
        fun strengthOf(desire: Double?) = ParityRecipe.read(task.copy(desire = desire)).volition.strength
        assertEquals(Strength.Unstated, strengthOf(null))
        assertEquals(Strength.None, strengthOf(0.0))
        assertEquals(Strength.None, strengthOf(-0.17), "a negative is below the scale, not weakly wanted")
        assertEquals(Strength.Weak, strengthOf(0.3))
        assertEquals(Strength.Strong, strengthOf(0.9))
        assertEquals(Strength.Strong, strengthOf(1.0))
    }

    @Test
    fun theServerDerivedReadinessFlagsAreCarriedAndNeverRecomputed() {
        // `blocked` inherits down the tree, so a row can be blocked with an empty edge list — and a
        // plugin that derived the flag from its own edges would read such a row as ready. The client
        // has never computed the readiness rules and this asserts it still does not.
        for (shape in KindShapes.ALL) {
            val (blocked, isBlocker) = when (shape) {
                is KindShape.OfTask -> shape.task.blocked to shape.task.isBlocker
                is KindShape.OfHabit -> shape.habit.blocked to shape.habit.isBlocker
                is KindShape.OfChore -> shape.chore.blocked to shape.chore.isBlocker
                is KindShape.OfEvent -> shape.event.blocked to shape.event.isBlocker
            }
            val blocker = readOf(shape).blocker
            assertEquals(blocked, blocker.blocked, "${shape.label}: the blocked flag was recomputed")
            assertEquals(isBlocker, blocker.isBlocker, "${shape.label}: the blocker flag was recomputed")
        }
        // The inherited case, stated directly: blocked with no edges is representable and must stay
        // blocked. The corpus generates it on all four kinds.
        val inherited = KindShapes.ALL.map(::readOf).filter { it.blocker.blocked && it.blocker.blockedBy.isEmpty() }
        assertTrue(inherited.isNotEmpty(), "the corpus stopped generating an inherited-blocked row")
    }

    @Test
    fun thePersistenceSeedReproducesTheOneBitTheKindStandsInForAndChangesNothing() {
        // The parity claim for a Family the wire cannot carry at all. There is nothing to diff
        // against — `carriesForward` is not a function in this client, only a discarded capture-time
        // input — so what is asserted is that the seed is *derivable from that one bit alone*, which
        // is what "no behaviour changes" means when the shipped answer is unwritten.
        for (kind in ItemKind.entries) {
            val expected = if (PersistenceSeed.carriesForward(kind)) {
                PersistencePolicy.UntilComplete
            } else {
                PersistencePolicy.ExpiresAfterWindow
            }
            assertEquals(expected, PersistenceSeed.of(kind), "$kind seeds a policy the bit does not imply")
        }

        // The bit itself, pinned as literals: Task and Chore roll forward, Habit and Event lapse.
        // Deliberately not re-derived from `PersistenceSeed.of` — a rule inverted in both places
        // would otherwise agree with itself.
        assertEquals(
            mapOf(
                ItemKind.Task to true,
                ItemKind.Chore to true,
                ItemKind.Habit to false,
                ItemKind.Event to false,
            ),
            ItemKind.entries.associateWith { PersistenceSeed.carriesForward(it) },
        )
    }

    @Test
    fun theSeedLeavesTheThreeRicherPoliciesUnreachable() {
        // The trap ADR-0056 names: the reference fixtures give a recurring chore a skipped-if-missed
        // policy, which LOGS the miss. Today nothing is logged. Seeding it would start writing
        // history nobody asked for while claiming to be a re-model, so a lapsing kind seeds
        // "gone, unrecorded" and the richer three wait for #420.
        val seeded = ItemKind.entries.map { PersistenceSeed.of(it) }.toSet()
        assertEquals(
            setOf(PersistencePolicy.UntilComplete, PersistencePolicy.ExpiresAfterWindow),
            seeded,
            "the parity seed reached a policy that changes behaviour",
        )
        // And the reading over the seed answers exactly the bit, so nothing downstream can diverge.
        for (kind in ItemKind.entries) {
            val expected = if (PersistenceSeed.carriesForward(kind)) Lapse.Persists else Lapse.Vanishes
            assertEquals(expected, Item(coreOf(kind), listOf(PersistenceSeed.of(kind))).atHorizon(), "$kind")
        }
    }

    @Test
    fun everyKindIsRepresentedInTheParityCorpus() {
        // The parity claim is per kind, so a corpus missing one would pass while asserting nothing
        // about it. Over `ItemKind.entries`, so a fifth kind cannot slip past this gate either.
        for (kind in ItemKind.entries) {
            assertTrue(
                KindShapes.ALL.any { it.kind == kind },
                "$kind has no shapes; the parity gate would be silent about it",
            )
        }
    }

    @Test
    fun theCorpusCarriesTheShapesEachReadingBranchesOn() {
        // Each reading above branches on something. If the corpus stops varying that something, the
        // assertion over it stays green without exercising the branch — the failure mode this test
        // exists to prevent.
        val labels = KindShapes.ALL.map { it.label }

        assertTrue(labels.any { it.contains("unanchored") }, "no undated shape — lateness would be vacuous")
        assertTrue(labels.any { it.contains("all-day-deadline") }, "no day-only deadline shape")
        assertTrue(labels.any { it.contains("timed-deadline") }, "no timed-deadline shape")
        assertTrue(labels.any { it.startsWith("Event/") && it.contains("start-and-end") }, "no Event window shape")

        assertTrue(labels.any { it.contains("Done") }, "no terminal Task shape")
        assertTrue(labels.any { it.contains("Archived") }, "no archived definition shape — Missed vs Skipped turns on it")

        assertTrue(labels.any { it.contains("no-rule") }, "no rule-less recurring shape")
        assertTrue(labels.any { it.contains("every-2-until") }, "no bounded-rule shape")
        assertTrue(labels.any { it.contains("elided-series") }, "no elided-series shape")
        assertTrue(labels.any { it.contains("unmodelled-mode") }, "no unmodelled cadence-mode shape")

        assertTrue(labels.any { it.contains("desire-0") }, "no zero-desire shape — 0.0 is a claim, not absence")
        assertTrue(labels.any { it.contains("no-desire") }, "no unstated-desire shape")

        // The Chore cadence-mode assertion above relies on `Unmodelled` surviving, which is only
        // interesting if it is actually generated.
        assertTrue(
            KindShapes.ALL.filterIsInstance<KindShape.OfChore>().any { it.chore.cadenceMode is CadenceMode.Unmodelled },
            "the corpus stopped generating an unmodelled cadence mode",
        )
    }

    // ── Reading a shape ────────────────────────────────────────────────────────────────────────

    /** A Core for a kind, for the seed assertions — which read a policy, not a row. */
    private fun coreOf(kind: ItemKind) =
        ParityRecipe.read(KindShapes.ALL.first { it.kind == ItemKind.Task }.let { (it as KindShape.OfTask).task }).core

    private fun readOf(shape: KindShape): Item = when (shape) {
        is KindShape.OfTask -> ParityRecipe.read(shape.task)
        is KindShape.OfHabit -> ParityRecipe.read(shape.habit)
        is KindShape.OfChore -> ParityRecipe.read(shape.chore)
        is KindShape.OfEvent -> ParityRecipe.read(shape.event)
    }
}
