package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.plugin.Anchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The time-of-day conflation, **reproduced on purpose** — this file exists so that nobody later
 * reads the reproduction as an oversight and "fixes" it.
 *
 * ### What the conflation is
 *
 * All four kinds carry a wire field called `complete_by`. On a Task, Habit or Chore it is a
 * **deadline**: due *by* that instant, satisfiable late, and `completionResolution` compares a
 * `doneAt` against it. On an Event it is a **start**: the moment the thing begins, with no late
 * concept at all — the backend's `validate_for_event` rejects `DoneLate` outright and this client's
 * occurrence mutation hard-codes `ItemKind.Event -> DoneOnTime` to match.
 *
 * One field name, two incompatible claims, and **no conversion between them anywhere**. Splitting
 * [Anchor.Deadline] from [Anchor.Appointment] gives the two claims separate names for the first
 * time. It changes nothing about which instant lands where, and this file is the assertion that it
 * changes nothing.
 *
 * ### Why the parity recipe may not correct it
 *
 * Correcting it means deciding what an existing row *meant* — whether a 17:00 on a Chore should
 * become an appointment, whether an Event's start should acquire a deadline, and what happens to the
 * rows already stored either way. That is a change to what a person sees, so it is #420's decision
 * and gets its own migration if it needs one. A recipe that quietly corrected it would also destroy
 * the gate: `writeX(read(x)) == x` would be measuring that two directions agreed on the same
 * rewrite, not that nothing was lost.
 */
class TemporalConflationTest {

    @Test
    fun theSameWireFieldBecomesADeadlineOnThreeKindsAndAStartOnTheFourth() {
        // The conflation stated as one assertion. Same field, same instant, two claims — and the
        // recipe reproduces the split exactly as today rather than reconciling it.
        val timed = KindShapes.ALL.filter { it.label.contains("timed-deadline") }
        assertTrue(timed.isNotEmpty(), "no timed-deadline shapes to read")
        for (shape in timed) {
            val anchor = anchorOf(shape)
            assertIs<Anchor.Deadline>(anchor, "${shape.label} should carry a deadline")
        }

        val events = KindShapes.ALL.filter { it.kind == ItemKind.Event && it.label.contains("start-only") }
        assertTrue(events.isNotEmpty(), "no Event window shapes to read")
        for (shape in events) {
            val anchor = anchorOf(shape)
            assertIs<Anchor.Appointment>(anchor, "${shape.label} should carry an appointment")
        }
    }

    @Test
    fun theInstantIsCarriedAcrossUnchangedOnBothSidesOfTheSplit() {
        // "No conversion" made falsifiable: whatever `completeBy` held goes into the anchor and
        // comes back out identical, whichever member of the family it landed in. A recipe that
        // shifted an Event's start to end-of-day, or moved a deadline's clock time onto its day,
        // would fail here before it reached the round-trip gate.
        for (shape in KindShapes.ALL) {
            val expected = completeByOf(shape)
            val actual = when (val anchor = anchorOf(shape)) {
                is Anchor.Deadline -> anchor.completeBy
                is Anchor.Appointment -> anchor.start
                Anchor.Unanchored -> null
            }
            assertEquals(expected, actual, "${shape.label} moved its instant")
        }
    }

    @Test
    fun latenessIsMeaningfulExactlyWhereItIsTodayAndNowhereElse() {
        // The one thing the split buys immediately: a rule hand-coded per kind on both sides of the
        // wire becomes readable off the shape. This asserts the derived answer equals the shipped
        // one — an Event is never late, the other three can be — over every dated shape in the
        // corpus rather than over one example of each.
        for (shape in KindShapes.ALL) {
            val anchor = anchorOf(shape)
            if (anchor == Anchor.Unanchored) continue
            val shipped = shape.kind != ItemKind.Event
            assertEquals(
                shipped,
                anchor.latenessIsMeaningful,
                "${shape.label}: lateness disagrees with what ships today",
            )
        }
    }

    @Test
    fun anEventsAllDayFlagIsCarriedRatherThanRecomputed() {
        // The server derives `all_day` from the two clock times and ignores it on input, but it
        // still ships the column — so a row where the flag and the times disagree is representable,
        // and the corpus generates exactly that. The recipe carries the stored flag; `isAllDay` is
        // the derived reading beside it. Both survive, and they are allowed to differ.
        // Only shapes that say something temporally: an Event with no window, no times and the flag
        // at its default is entirely degenerate and reads as `Unanchored`, so there is no stored
        // flag there to be faithful to.
        val disagreeing = KindShapes.ALL
            .filterIsInstance<KindShape.OfEvent>()
            .filter { it.event.allDay != (it.event.startTimeOfDay == null && it.event.endTimeOfDay == null) }
            .filter { anchorOf(it) is Anchor.Appointment }
        assertTrue(disagreeing.isNotEmpty(), "the corpus stopped generating a disagreeing all-day flag")

        for (shape in disagreeing) {
            val anchor = assertIs<Anchor.Appointment>(anchorOf(shape))
            assertEquals(shape.event.allDay, anchor.allDayFlag, "${shape.label} rewrote the stored flag")
            assertEquals(
                shape.event.startTimeOfDay == null && shape.event.endTimeOfDay == null,
                anchor.isAllDay,
                "${shape.label}: the derived reading is not derived from the times",
            )
        }
    }

    @Test
    fun aClockTimeWithNoDaySurvivesRatherThanBeingTidiedAway() {
        // A deadline whose day is absent is meaningless and the corpus does not generate it — but
        // the wire can carry it, so the plugin's fields are both nullable and the recipe builds a
        // Deadline whenever EITHER is present. Asserted directly, because the round-trip gate
        // cannot see a case the corpus does not contain.
        val anchor = assertIs<Anchor.Deadline>(
            ParityRecipe.read(
                KindShapes.ALL.filterIsInstance<KindShape.OfTask>().first().task
                    .copy(completeBy = null, deadlineTimeOfDay = kotlinx.datetime.LocalTime(17, 0)),
            ).anchor,
        )
        assertEquals(null, anchor.completeBy)
        assertEquals(kotlinx.datetime.LocalTime(17, 0), anchor.timeOfDay)
    }

    // ── Reading a shape ────────────────────────────────────────────────────────────────────────

    private fun anchorOf(shape: KindShape): Anchor = when (shape) {
        is KindShape.OfTask -> ParityRecipe.read(shape.task).anchor
        is KindShape.OfHabit -> ParityRecipe.read(shape.habit).anchor
        is KindShape.OfChore -> ParityRecipe.read(shape.chore).anchor
        is KindShape.OfEvent -> ParityRecipe.read(shape.event).anchor
    }

    private fun completeByOf(shape: KindShape) = when (shape) {
        is KindShape.OfTask -> shape.task.completeBy
        is KindShape.OfHabit -> shape.habit.completeBy
        is KindShape.OfChore -> shape.chore.completeBy
        is KindShape.OfEvent -> shape.event.completeBy
    }
}
