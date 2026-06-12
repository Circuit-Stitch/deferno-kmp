package com.circuitstitch.deferno.core.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The #147 acceptance round-trip: a kotlinx-serializable schema travels through the
 * [InferenceEngine] seam via [FakeInferenceEngine] — typed request in, validated instance out —
 * and every canned failure mode surfaces typed, never thrown.
 */
class FakeInferenceEngineTest {

    private val schema = InferenceSchema(DraftTasks.serializer())

    private fun request() = InferenceRequest(
        instructions = "Extract draft tasks from the transcript.",
        content = "mow the lawn, then sharpen the mower blades by Friday",
        schema = schema,
    )

    @Test
    fun roundTripsASerializableSchemaToAValidatedInstance() = runTest {
        val engine = FakeInferenceEngine()
        val canned = DraftTasks(
            drafts = listOf(
                Draft(title = "Mow the lawn", productive = true),
                Draft(title = "Sharpen the mower blades", completeBy = "2026-06-12"),
            ),
        )
        engine.enqueue(canned)

        val result = engine.infer(request())

        // Value equality across the encode→decode trip is the round-trip proof: what the seam
        // returns is what the schema serializes to and parses back from.
        assertEquals(canned, assertIs<InferenceResult.Success<DraftTasks>>(result).value)
    }

    @Test
    fun surfacesCannedFailuresTypedRatherThanThrown() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(InferenceResult.Failure.MalformedOutput("schema validation failed"))

        val result = engine.infer(request())

        assertEquals(
            InferenceResult.Failure.MalformedOutput("schema validation failed"),
            result,
        )
    }

    @Test
    fun reportsNotConfiguredWhenNothingIsEnqueued() = runTest {
        val result = FakeInferenceEngine().infer(request())

        assertIs<InferenceResult.Failure.NotConfigured>(result)
    }

    @Test
    fun recordsTheRequestsItServes() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(DraftTasks(drafts = emptyList()))

        engine.infer(request())

        val seen = engine.requests.single()
        assertEquals("Extract draft tasks from the transcript.", seen.instructions)
        assertEquals("draft_tasks", seen.schema.name)
    }
}
