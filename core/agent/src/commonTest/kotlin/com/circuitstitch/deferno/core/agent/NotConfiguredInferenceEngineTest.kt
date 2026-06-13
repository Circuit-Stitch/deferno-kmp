package com.circuitstitch.deferno.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertTrue

class NotConfiguredInferenceEngineTest {

    @Serializable
    private data class Anything(val value: String)

    @Test
    fun everyCallIsNotConfigured() = runTest {
        val result = NotConfiguredInferenceEngine.infer(
            InferenceRequest(
                instructions = "irrelevant",
                content = "irrelevant",
                schema = InferenceSchema(Anything.serializer()),
            ),
        )
        assertTrue(result is InferenceResult.Failure.NotConfigured)
    }
}
