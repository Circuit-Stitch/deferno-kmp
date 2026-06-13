package com.circuitstitch.deferno.core.agent

/**
 * The inference floor (ADR-0027 / ADR-0029): every call answers
 * [InferenceResult.Failure.NotConfigured] **without making any network request**, so the merged DI
 * graph always resolves an [InferenceEngine] and nothing off-device can ever happen silently.
 *
 * Bound on targets where no concrete engine ships — currently macOS, until its Swift FoundationModels
 * engine is injected at this same seam (ADR-0029, Phase 3). The Koog-backed engine fills the seam on
 * Android/JVM/iOS.
 */
object NotConfiguredInferenceEngine : InferenceEngine {
    override suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T> =
        InferenceResult.Failure.NotConfigured("no inference engine configured on this platform")
}
