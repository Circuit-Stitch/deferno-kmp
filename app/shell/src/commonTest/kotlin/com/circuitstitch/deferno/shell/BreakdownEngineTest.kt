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
        prerequisites: BreakdownEngine.Prerequisites = BreakdownEngine.Prerequisites(),
    ) = BreakdownEngine(
        root = BreakdownEngine.ItemContext(id = id, title = title),
        classifier = ScriptedClassifier(script),
        moves = moves,
        prerequisites = prerequisites,
    )

    @Test
    fun starts_by_asking_about_the_root() {
        val e = engine(emptyList(), SpyMoves())
        assertEquals(BreakdownEngine.Phase.Asking, e.phase.value)
        assertEquals("Clean the garage", e.focusTitle.value)
        val last = e.messages.value.last()
        assertEquals(BreakdownEngine.Role.Assistant, last.role)
        assertEquals(BreakdownEngine.MessageKind.WhatsStopping, last.kind)
        assertEquals("Clean the garage", last.arg)
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

        // The person's own words ride through verbatim (user content, never localized) …
        val userLine = e.messages.value.first { it.role == BreakdownEngine.Role.User }
        assertEquals(BreakdownEngine.MessageKind.UserText, userLine.kind)
        assertEquals("it's just too big", userLine.arg)
        // … and the "broke it into" line carries the created titles for the View to list.
        val broke = e.messages.value.first { it.kind == BreakdownEngine.MessageKind.BrokeInto }
        assertEquals(listOf("Clear the workbench", "Sort the bins"), broke.args)
        assertEquals(BreakdownEngine.MessageKind.FinishedReady, e.messages.value.last().kind)
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
        val added = e.messages.value.first { it.kind == BreakdownEngine.MessageKind.AddedPrerequisite }
        assertEquals("Research disposal options", added.arg)
    }

    @Test
    fun a_missing_prerequisite_title_falls_back_to_the_injected_defaults_per_kind() = runTest {
        // The fallback titles are *persisted* server-synced Task content, so the platform injects them
        // localized at engine creation (BreakdownScreen resolves breakdown_prereq_* via getString).
        val spy = SpyMoves()
        val e = engine(
            listOf(
                ImpedimentClassification(ImpedimentClass.dontKnowHow), // root: no prerequisiteTitle
                ImpedimentClassification(ImpedimentClass.scaredOfDoingItWrong, prerequisiteTitle = "  "), // child-1: blank
                ImpedimentClassification(ImpedimentClass.nothingStopping), // child-2: ready → finishes
            ),
            spy,
            prerequisites = BreakdownEngine.Prerequisites(
                figureOutHow = "Cómo hacerlo",
                defineDone = "Definir “hecho”",
            ),
        )

        e.submit("no idea where to start")
        e.submit("what if I mess it up")
        e.submit("nothing")

        assertEquals(listOf("Cómo hacerlo", "Definir “hecho”"), spy.capturedTitles)
        assertEquals(listOf("root", "child-1"), spy.capturedParents)
        assertEquals(
            listOf("Cómo hacerlo", "Definir “hecho”"),
            e.messages.value.filter { it.kind == BreakdownEngine.MessageKind.AddedPrerequisite }.map { it.arg },
        )
        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Ready), e.phase.value)
    }

    @Test
    fun the_default_prerequisite_titles_are_the_english_fallbacks() = runTest {
        val spy = SpyMoves()
        val e = engine(
            listOf(
                ImpedimentClassification(ImpedimentClass.scaredOfDoingItWrong), // no prerequisiteTitle
                ImpedimentClassification(ImpedimentClass.nothingStopping), // the prerequisite child
            ),
            spy,
        )

        e.submit("what if I do it wrong")
        e.submit("nothing")

        assertEquals(
            listOf("Define what “done” looks like, and decide if I'm the right person or should delegate"),
            spy.capturedTitles,
        )
    }

    @Test
    fun persistent_avoidance_confirmed_drops_the_item() = runTest {
        val spy = SpyMoves()
        val e = engine(listOf(ImpedimentClassification(ImpedimentClass.persistentAvoidance)), spy)

        e.submit("honestly I just never want to")
        assertEquals(BreakdownEngine.Phase.ConfirmingDrop, e.phase.value)
        val offer = e.messages.value.last()
        assertEquals(BreakdownEngine.MessageKind.ConfirmDrop, offer.kind)
        assertEquals("Clean the garage", offer.arg)

        e.confirmDrop(true)
        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Dropped), e.phase.value)
        assertEquals(listOf("root"), spy.dropped)
        val dropped = e.messages.value.first { it.kind == BreakdownEngine.MessageKind.Dropped }
        assertEquals("Clean the garage", dropped.arg)
        assertEquals(BreakdownEngine.MessageKind.FinishedDropped, e.messages.value.last().kind)
    }

    @Test
    fun persistent_avoidance_declined_keeps_the_item_and_re_asks() = runTest {
        val spy = SpyMoves()
        val e = engine(listOf(ImpedimentClassification(ImpedimentClass.persistentAvoidance)), spy)

        e.submit("never want to")
        e.confirmDrop(false)

        assertEquals(BreakdownEngine.Phase.Asking, e.phase.value)
        assertTrue(spy.dropped.isEmpty())
        val kept = e.messages.value.last()
        assertEquals(BreakdownEngine.MessageKind.KeptReask, kept.kind)
        assertEquals("Clean the garage", kept.arg)
    }

    @Test
    fun nothing_stopping_is_ready_and_can_go_on_todays_plan() = runTest {
        val spy = SpyMoves()
        val e = engine(listOf(ImpedimentClassification(ImpedimentClass.nothingStopping)), spy)

        e.submit("nothing really, I can start")
        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Ready), e.phase.value)
        val ready = e.messages.value.first { it.kind == BreakdownEngine.MessageKind.ReadyToGo }
        assertEquals("Clean the garage", ready.arg)

        e.addRootToPlan()
        assertEquals(listOf("root"), spy.planned)
        assertEquals(BreakdownEngine.MessageKind.AddedToPlan, e.messages.value.last().kind)
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
        assertEquals(BreakdownEngine.MessageKind.ClassifierRetry, e.messages.value.last().kind)
    }

    @Test
    fun bail_ends_the_flow() {
        val e = engine(emptyList(), SpyMoves())
        e.bail()
        assertEquals(BreakdownEngine.Phase.Finished(BreakdownEngine.Outcome.Bailed), e.phase.value)
    }
}
