package com.circuitstitch.deferno.core.agent

import kotlinx.serialization.json.Json

/**
 * The in-memory [InferenceEngine] test double (#147): serves canned results in FIFO order. The
 * success path is honest about serialization — a canned value is round-tripped through the request's
 * [InferenceSchema.serializer] (encode → decode, the same trip real model output makes), so a schema
 * that can't survive the wire fails in the test that uses it, not in production.
 */
class FakeInferenceEngine(
    private val json: Json = Json,
) : InferenceEngine {

    private val canned = ArrayDeque<InferenceResult<Any>>()

    /** Every request served, oldest first — assert on prompt + schema wiring. */
    val requests = mutableListOf<InferenceRequest<*>>()

    /** Enqueue a canned success; [value]'s type must match the schema of the request it answers. */
    fun enqueue(value: Any) {
        canned += InferenceResult.Success(value)
    }

    /** Enqueue a canned typed failure. */
    fun enqueue(failure: InferenceResult.Failure) {
        canned += failure
    }

    override suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T> {
        requests += request
        val next = canned.removeFirstOrNull()
            ?: InferenceResult.Failure.NotConfigured("FakeInferenceEngine: nothing enqueued")
        return when (next) {
            is InferenceResult.Failure -> next
            is InferenceResult.Success -> {
                @Suppress("UNCHECKED_CAST")
                val value = next.value as T
                val wire = json.encodeToString(request.schema.serializer, value)
                InferenceResult.Success(json.decodeFromString(request.schema.serializer, wire))
            }
        }
    }
}
