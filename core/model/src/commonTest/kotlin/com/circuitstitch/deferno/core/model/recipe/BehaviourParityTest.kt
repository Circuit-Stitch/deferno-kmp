package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ADR-0056 **behaviour-parity gate**: the readings derived from a plugin set equal what ships
 * today, asserted rather than reviewed.
 *
 * ### This slice lands the harness, not the assertions (#417)
 *
 * There are no plugin-derived readings yet — the Families they read land in #418 and #419 — so this
 * file is the wiring: it runs on `check` across all four KMP test targets over the same
 * [KindShapes] corpus the round-trip gate sweeps, and it names the four readings that must not move
 * so the next slice fills bodies rather than deciding scope.
 *
 * ### The four readings, and where each lives today
 *
 * Named here because finding them cost more than writing them down, and because two of the four are
 * not where ADR-0056's shorthand implies:
 *
 *  - **Lapse (`carriesForward`).** *There is no `carriesForward` function in this client.* The only
 *    thing bearing the name is `IfMissed.CarriesForward` in `core:domain`'s `CaptureInput` — a
 *    capture-time input that **derives the kind** (carries-forward → Chore, lapses → Habit), not a
 *    reading over an existing row. So the parity claim in #419 cannot be "reproduce
 *    `carriesForward`"; it is "reproduce the one-bit answer the kind stands in for", which the epic
 *    states as Task and Chore roll forward, Habit and Event do not. That bit is *unwritten* today,
 *    and the Persistence seed is the first place it becomes a function. Worth flagging on #419
 *    before its parity seed is built against a function that is not there.
 *  - **Progress (`resolveOccurrenceState`).** `OccurrenceStateResolver.kt` — a total function of
 *    `(fact, covered, definitionState, date, today)`, already pinned by 14 tests. Its inputs are per
 *    *firing*, not per definition, so #418's assertions need an occurrence corpus beside the
 *    definition corpus this file sweeps.
 *  - **Lateness.** Not in `NextDeadline.kt` (that is Chore-only rolling-cadence advancement and
 *    computes no lateness at all). It is `completionResolution(doneAt, completeBy)` in
 *    `OccurrenceFact.kt` — inclusive bound, a null deadline can never be late — branched per kind in
 *    `core:data`'s occurrence mutation, where an Event hard-codes `DoneOnTime` because the server
 *    rejects a late Event outright.
 *  - **The temporal anchor.** An Event's `completeBy` is a **start**; the same field on the other
 *    three is a **deadline**, with no conversion between the two claims. The parity recipe
 *    reproduces that conflation faithfully — correcting it is #420's decision, not this gate's.
 *
 * ### Why it does not assert on today's readings yet
 *
 * A characterisation test that recomputes a shipping function and asserts it equals itself is green
 * by construction and pins nothing. The parity claim only becomes falsifiable once there is a second
 * way to compute the answer — the plugin-derived one — which is exactly what #418 adds. Until then
 * this file guards the corpus these assertions will stand on.
 */
class BehaviourParityTest {

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
        // Each reading listed in the class KDoc branches on something. If the corpus does not vary
        // that something, #418's assertion over it is green without exercising the branch — the
        // failure mode this test exists to prevent, checked now so the corpus is already fit when
        // the assertions arrive.
        val labels = KindShapes.ALL.map { it.label }

        // Lateness + the anchor conflation: a deadline that is only a day, one that carries a clock
        // time, and none at all — plus the Event window, which is the same field meaning a start.
        assertTrue(labels.any { it.contains("unanchored") }, "no undated shape — lateness can never be vacuous")
        assertTrue(labels.any { it.contains("all-day-deadline") }, "no day-only deadline shape")
        assertTrue(labels.any { it.contains("timed-deadline") }, "no timed-deadline shape")
        assertTrue(labels.any { it.startsWith("Event/") && it.contains("start-and-end") }, "no Event window shape")

        // Progress: the definition-side lifecycle both readings key off.
        assertTrue(labels.any { it.contains("Done") }, "no terminal Task shape")
        assertTrue(labels.any { it.contains("Archived") }, "no archived definition shape — Missed vs Skipped turns on it")

        // Lapse: the recurring-vs-one-off split the one-bit answer is read off today.
        assertTrue(labels.any { it.contains("no-rule") }, "no rule-less recurring shape")
        assertTrue(labels.any { it.contains("every-2-until") }, "no bounded-rule shape")
    }
}
