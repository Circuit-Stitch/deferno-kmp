package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.agent.BreakdownClassifierException
import com.circuitstitch.deferno.core.agent.ImpedimentClass
import com.circuitstitch.deferno.core.agent.ImpedimentClassification
import com.circuitstitch.deferno.core.agent.ImpedimentClassifier
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LLM-free coverage of the [BreakdownEngine] state machine (Deferno#525) — the Kotlin twin of the iOS
 * `BreakdownEngineTests`. We assert **external behavior**: which moves run with what args, given a scripted
 * sequence of classified answers, and the terminal phase — never internals. A [ScriptedClassifier] stands in
 * for the on-device model and a [SpyMoves] records the offline-first moves, so the whole flow runs with none.
 */
class BreakdownEngineTest {

    /** Returns scripted classifications in call order (the engine is deterministic, so order is stable). */
    private class ScriptedClassifier(
        script: List<ImpedimentClassification>,
        private val fallback: ImpedimentClassification = ImpedimentClassification(ImpedimentClass.nothingStopping),
    ) : ImpedimentClassifier {
        private val queue = ArrayDeque(script)
        override suspend fun classify(answer: String, taskTitle: String, taskNotes: String?): ImpedimentClassification =
            queue.removeFirstOrNull() ?: fallback
    }

    /** A classifier that always fails — to assert the graceful "rephrase" recovery. */
    private object FailingClassifier : ImpedimentClassifier {
        override suspend fun classify(answer: String, taskTitle: String, taskNotes: String?): ImpedimentClassification =
            throw BreakdownClassifierException("boom")
    }

    /** Records every move + mints child ids so recursion has something to descend into. */
    private class SpyMoves : BreakdownActions {
        val capturedParents = mutableListOf<String>()
        val capturedTitles = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val planned = mutableListOf<String>()
        private var n = 0
        override suspend fun captureSubtask(parentId: String, title: String): String? {
            capturedParents += parentId
            capturedTitles += title
            n += 1
            return "child-$n"
        }
        override suspend fun drop(taskId: String) { dropped += taskId }
        override suspend fun addToPlan(taskId: String) { planned += taskId }
    }

    private fun engine(
        script: List<ImpedimentClassification>,
        moves: BreakdownActions,
        title: String = "Clean the garage",
        id: String = "root",
    ) = BreakdownEngine(
        root = BreakdownEngine.ItemContext(id = id, title = title),
        classifier = ScriptedClassifier(script),
        moves = moves,
    )

    @Test
    fun starts_by_asking_about_the_root() {
        val e = engine(emptyList(), SpyMoves())
        assertEquals(BreakdownEngine.Phase.Asking, e.phase.value)
        assertEquals("Clean the garage", e.focusTitle.value)
        assertEquals(BreakdownEngine.Role.Assistant, e.messages.value.last().role)
    }

    @Test
    fun too_big_captures_subtasks_under_the_root_then_recurses_to_ready() = runTest {
        val spy = SpyMoves()
        val e = engine(
            listOf(
                ImpedimentClassification(ImpedimentClass.tooBig, subtaskTitles = listOf("Clear the workbench", "Sort the bins")),
                ImpedimentClassification(ImpedimentClass.nothingStopping), // first child
                ImpedimentClassification(ImpedimentClass.nothingStopping), // second child
            ),
            spy,
        )

        e.submit("it's just too big")
        e.submit("nothing")
        e.submit("nothing")

        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Ready), e.phase.value)
        assertEquals(listOf("Clear the workbench", "Sort the bins"), spy.capturedTitles)
        assertEquals(listOf("root", "root"), spy.capturedParents)
        assertTrue(spy.dropped.isEmpty())
    }

    @Test
    fun dont_know_how_spins_off_a_prerequisite_subtask() = runTest {
        val spy = SpyMoves()
        val e = engine(
            listOf(
                ImpedimentClassification(ImpedimentClass.dontKnowHow, prerequisiteTitle = "Research disposal options"),
                ImpedimentClassification(ImpedimentClass.nothingStopping), // the prerequisite child
            ),
            spy,
        )

        e.submit("I don't know how")
        e.submit("nothing")

        assertEquals(listOf("Research disposal options"), spy.capturedTitles)
        assertEquals(listOf("root"), spy.capturedParents)
        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Ready), e.phase.value)
    }

    @Test
    fun persistent_avoidance_confirmed_drops_the_item() = runTest {
        val spy = SpyMoves()
        val e = engine(listOf(ImpedimentClassification(ImpedimentClass.persistentAvoidance)), spy)

        e.submit("honestly I just never want to")
        assertEquals(BreakdownEngine.Phase.ConfirmingDrop, e.phase.value)

        e.confirmDrop(true)
        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Dropped), e.phase.value)
        assertEquals(listOf("root"), spy.dropped)
    }

    @Test
    fun persistent_avoidance_declined_keeps_the_item_and_re_asks() = runTest {
        val spy = SpyMoves()
        val e = engine(listOf(ImpedimentClassification(ImpedimentClass.persistentAvoidance)), spy)

        e.submit("never want to")
        e.confirmDrop(false)

        assertEquals(BreakdownEngine.Phase.Asking, e.phase.value)
        assertTrue(spy.dropped.isEmpty())
    }

    @Test
    fun nothing_stopping_is_ready_and_can_go_on_todays_plan() = runTest {
        val spy = SpyMoves()
        val e = engine(listOf(ImpedimentClassification(ImpedimentClass.nothingStopping)), spy)

        e.submit("nothing really, I can start")
        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Ready), e.phase.value)

        e.addRootToPlan()
        assertEquals(listOf("root"), spy.planned)
    }

    @Test
    fun transient_obstacle_makes_no_structural_change() = runTest {
        val spy = SpyMoves()
        val e = engine(listOf(ImpedimentClassification(ImpedimentClass.transientObstacle)), spy)

        e.submit("it's raining")

        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Ready), e.phase.value)
        assertTrue(spy.capturedTitles.isEmpty())
        assertTrue(spy.dropped.isEmpty())
    }

    @Test
    fun blocked_and_urgent_degrade_without_mutating_the_graph() = runTest {
        for (stuck in listOf(ImpedimentClass.waitingOnDependency, ImpedimentClass.somethingMoreUrgent)) {
            val spy = SpyMoves()
            val e = engine(listOf(ImpedimentClassification(stuck)), spy)

            e.submit("…")

            assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Ready), e.phase.value)
            assertTrue(spy.capturedTitles.isEmpty())
            assertTrue(spy.dropped.isEmpty())
            assertTrue(spy.planned.isEmpty())
        }
    }

    @Test
    fun a_classifier_failure_lets_the_person_rephrase() = runTest {
        val e = BreakdownEngine(
            root = BreakdownEngine.ItemContext("root", "X"),
            classifier = FailingClassifier,
            moves = SpyMoves(),
        )

        e.submit("uhh")
        assertEquals(BreakdownEngine.Phase.Asking, e.phase.value)
    }

    @Test
    fun bail_ends_the_flow() {
        val e = engine(emptyList(), SpyMoves())
        e.bail()
        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Bailed), e.phase.value)
    }
}
