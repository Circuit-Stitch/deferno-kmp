package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.plugin.Carrot
import com.circuitstitch.deferno.core.model.plugin.Dynamics
import com.circuitstitch.deferno.core.model.plugin.Evaluation
import com.circuitstitch.deferno.core.model.plugin.Force
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Obligation
import com.circuitstitch.deferno.core.model.plugin.PersistencePolicy
import com.circuitstitch.deferno.core.model.plugin.Plugin
import com.circuitstitch.deferno.core.model.plugin.Prioritizable
import com.circuitstitch.deferno.core.model.plugin.Purpose
import com.circuitstitch.deferno.core.model.plugin.Reach
import com.circuitstitch.deferno.core.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ADR-0057's two promises, made falsifiable: **a shadowed value never breaks the wire-backed half,
 * and a refresh from the server never clears it.**
 *
 * Both are properties of a boundary rather than of a feature, which is why they are worth a test of
 * their own. The failure they guard against is quiet in both directions — a shadowed value that
 * leaks into a mutation becomes a row that can never sync, and one that a refresh clears is data a
 * person recorded and then watched disappear on the next pull.
 *
 * The device-local store itself is a later slice. Everything here holds in memory, which is enough:
 * the boundary is the claim, not the storage.
 */
class ShadowFamilyTest {

    /** One of each shadowed Family, at a value that is not its degenerate one. */
    private val shadowed: List<Plugin> = listOf(
        Dynamics.Telic("passport in hand"),
        Purpose(listOf(Carrot.InWords("go to Japan"))),
        Obligation(Force.Must),
        PersistencePolicy.SkippedIfMissed,
    )

    @Test
    fun theWireBackedHalfRoundTripsUntouchedWhenShadowedFamiliesArePresent() {
        // THE promise. Every shape in the corpus, read into plugins, given four shadowed values it
        // has no wire field for, and written back — and the row that comes out is the row that went
        // in. A recipe that noticed the extra plugins at all would fail here.
        val failures = mutableListOf<String>()
        for (shape in KindShapes.ALL) {
            val withShadow = readOf(shape).let { it.copy(plugins = it.plugins + shadowed) }
            val roundTripped: Any = when (shape.kind) {
                ItemKind.Task -> ParityRecipe.writeTask(withShadow)
                ItemKind.Habit -> ParityRecipe.writeHabit(withShadow)
                ItemKind.Chore -> ParityRecipe.writeChore(withShadow)
                ItemKind.Event -> ParityRecipe.writeEvent(withShadow)
            }
            if (roundTripped != rowOf(shape)) failures += shape.label
        }
        assertEquals(emptyList(), failures, "shadowed families disturbed the wire-backed round trip")
    }

    @Test
    fun aRefreshFromTheServerNeverClearsAShadowedValue() {
        // The server is authoritative for everything it has a field for, and for NOTHING else.
        // Replacing the cached item wholesale is the naive move and it silently destroys the four
        // values below on the next pull.
        val shape = KindShapes.ALL.first { it.label.startsWith("Task/saturated") }
        val held = readOf(shape).let { it.copy(plugins = it.plugins + shadowed) }

        // What comes back from a refresh: the same row, wire-backed only, with one field changed.
        val fromServer = ParityRecipe.read(
            ParityRecipe.writeTask(held).copy(title = "Renew the passport (urgent)"),
        )

        val merged = held.refreshedFrom(fromServer)

        assertEquals("Renew the passport (urgent)", merged.core.title, "the server's change was dropped")
        assertEquals(
            shadowed.toSet(),
            merged.plugins.filter { it.reach == Reach.DeviceLocal }.toSet(),
            "a refresh cleared a shadowed value",
        )
        assertEquals(
            fromServer.plugins.filter { it.reach == Reach.Wire },
            merged.plugins.filter { it.reach == Reach.Wire },
            "the refresh did not win on the half it owns",
        )
    }

    @Test
    fun aRefreshThatDropsAWireBackedFieldIsHonouredRatherThanMerged() {
        // The other side of the same promise, and the one that is easy to get wrong by being too
        // careful: the server IS authoritative for its own half, so a field it stops sending is a
        // field that is gone. Preserving it "just in case" would resurrect deleted values.
        val shape = KindShapes.ALL.first { it.label.startsWith("Task/saturated") }
        val held = readOf(shape)
        val stripped = ParityRecipe.read(ParityRecipe.writeTask(held).copy(labels = emptyList()))

        val merged = held.refreshedFrom(stripped)

        assertEquals(emptyList(), merged.taggable.labels, "a label the server dropped came back")
    }

    @Test
    fun theClampAdmitsAShadowedSetAndNamesWhatCannotBeSent() {
        // Representable, but not sent — the middle path ADR-0057 takes between silently dropping and
        // silently sending. What comes back is exactly the two lists a caller needs: the row to
        // enqueue, and the values to mark as unsynced wherever a person can act on them.
        val shape = KindShapes.ALL.first { it.label.startsWith("Task/saturated") }
        val held = readOf(shape).let { it.copy(plugins = it.plugins + shadowed) }

        val admission = assertIs<Admission.Admitted>(Clamp.admit(held, ItemKind.Task))
        assertTrue(admission.hasUnsynced, "a set carrying four shadowed values reported none")
        assertEquals(shadowed.toSet(), admission.notSynced.toSet())
        assertTrue(
            admission.synced.plugins.none { it.reach == Reach.DeviceLocal },
            "a device-local plugin reached the half that goes to the server",
        )
    }

    @Test
    fun theClampAdmitsEveryShapeTheRecipesProduce() {
        // The clamp exists for sets a CALLER assembles; it must never fire on the recipes' own
        // output. If it did, the round-trip gate and the clamp would disagree about what is
        // representable, and the clamp would be refusing rows the app itself created.
        for (shape in KindShapes.ALL) {
            val admission = Clamp.admit(readOf(shape), shape.kind)
            assertIs<Admission.Admitted>(admission, "${shape.label} was refused by the clamp")
            assertTrue(!admission.hasUnsynced, "${shape.label}: a recipe produced a device-local plugin")
        }
    }

    @Test
    fun theClampRefusesAWireBackedValueTheKindHasNoFieldFor() {
        // The plugin model is wider than any one kind. A Habit carries no `desire` and no attachment
        // rollup on the wire, so a set claiming them cannot be held as a Habit — and the refusal
        // names each value rather than quietly writing a row that loses them.
        val habit = KindShapes.ALL.first { it.kind == ItemKind.Habit && it.label.contains("minimal") }
        val overreaching = readOf(habit).let {
            it.copy(
                plugins = it.plugins +
                    com.circuitstitch.deferno.core.model.plugin.Volition(0.5) +
                    com.circuitstitch.deferno.core.model.plugin.Attachable(2, 4096),
            )
        }

        val refused = assertIs<Admission.Refused>(Clamp.admit(overreaching, ItemKind.Habit))
        assertTrue(refused.reasons.any { it.contains("Volition") }, refused.reasons.toString())
        assertTrue(refused.reasons.any { it.contains("Attachable") }, refused.reasons.toString())
    }

    @Test
    fun theClampRefusesAnInvalidSetBeforeItAsksAboutTheWire() {
        // Two members of one family is a defect regardless of which kind it is being held as, so it
        // is caught as itself rather than reported as a wire limitation.
        val shape = KindShapes.ALL.first { it.kind == ItemKind.Task }
        val doubled = readOf(shape).let {
            it.copy(plugins = it.plugins + Prioritizable(Priority.Fire) + Prioritizable(Priority.Backlog))
        }
        val refused = assertIs<Admission.Refused>(Clamp.admit(doubled, ItemKind.Task))
        assertTrue(refused.reasons.any { it.contains("exclusive") }, refused.reasons.toString())
    }

    @Test
    fun anOccurrenceScopedShadowedValueSitsOnTheOccurrenceAndNotTheItem() {
        // The verdict is the one shadowed member that belongs to a date rather than to the
        // definition, and placement is checked the same way it is for everything else — by a filter
        // over `scope`, with no special case for being shadowed.
        val shape = KindShapes.ALL.first { it.kind == ItemKind.Task }
        val misplaced = readOf(shape).let { it.copy(plugins = it.plugins + Evaluation(obtained = true)) }
        assertTrue(
            misplaced.validate().any { it.contains("Evaluation") },
            "a verdict on the definition was accepted: ${misplaced.validate()}",
        )
    }

    // ── Reading a shape ────────────────────────────────────────────────────────────────────────

    private fun readOf(shape: KindShape): Item = when (shape) {
        is KindShape.OfTask -> ParityRecipe.read(shape.task)
        is KindShape.OfHabit -> ParityRecipe.read(shape.habit)
        is KindShape.OfChore -> ParityRecipe.read(shape.chore)
        is KindShape.OfEvent -> ParityRecipe.read(shape.event)
    }

    private fun rowOf(shape: KindShape): Any = when (shape) {
        is KindShape.OfTask -> shape.task
        is KindShape.OfHabit -> shape.habit
        is KindShape.OfChore -> shape.chore
        is KindShape.OfEvent -> shape.event
    }
}
