package com.circuitstitch.deferno.core.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImpedimentClassifierTest {

    @Test
    fun classify_maps_a_successful_reply_and_feeds_the_task_as_context() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(
            InferenceImpedimentClassifier.Schema(
                impediment = ImpedimentClass.tooBig,
                subtaskTitles = listOf("Clear the workbench", "Sort the bins"),
                prerequisiteTitle = "",
            ),
        )

        val result = InferenceImpedimentClassifier(engine).classify(
            answer = "it's just too big",
            taskTitle = "Clean the garage",
            taskNotes = "the whole left bay",
        )

        assertEquals(ImpedimentClass.tooBig, result.kind)
        assertEquals(listOf("Clear the workbench", "Sort the bins"), result.subtaskTitles)
        assertNull(result.prerequisiteTitle) // an empty prerequisite normalizes to null

        // The request carries the impediment schema + the task & answer as the model's context.
        val request = engine.requests.single()
        assertEquals("impediment_classification", request.schema.name)
        assertTrue(request.content.contains("Clean the garage"))
        assertTrue(request.content.contains("the whole left bay"))
        assertTrue(request.content.contains("it's just too big"))
    }

    @Test
    fun classify_keeps_a_non_blank_prerequisite_title() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(
            InferenceImpedimentClassifier.Schema(
                impediment = ImpedimentClass.dontKnowHow,
                prerequisiteTitle = "Research disposal options",
            ),
        )

        val result = InferenceImpedimentClassifier(engine).classify("dunno how", "X", null)

        assertEquals(ImpedimentClass.dontKnowHow, result.kind)
        assertEquals("Research disposal options", result.prerequisiteTitle)
    }

    @Test
    fun classify_throws_a_typed_exception_on_an_inference_failure() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(InferenceResult.Failure.NotConfigured("no engine"))

        assertFailsWith<BreakdownClassifierException> {
            InferenceImpedimentClassifier(engine).classify("uhh", "X", null)
        }
    }
}
