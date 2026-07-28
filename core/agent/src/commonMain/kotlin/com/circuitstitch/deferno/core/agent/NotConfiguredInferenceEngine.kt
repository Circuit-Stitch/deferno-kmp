package com.circuitstitch.deferno.core.agent

/**
 * The inference floor (ADR-0027 / ADR-0029): every call answers
 * [InferenceResult.Failure.NotConfigured] **without making any network request**, so the merged DI
 * graph always resolves an [InferenceEngine] and nothing off-device can ever happen silently.
 *
 * No longer any target's whole binding: every platform now binds the [RoutingInferenceEngine], and this
 * is what the router falls through to when the selected id has no `@IntoMap` entry — [InferenceEngineId.Off]
 * anywhere, and a **cloud** selection on macOS, which ships no Koog klib and so registers no cloud engine
 * (ADR-0029). It is also what the Apple on-device forwarders delegate to until the Swift Foundation Models
 * adapter is installed at app launch (ADR-0037).
 */
object NotConfiguredInferenceEngine : InferenceEngine {
    override suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T> =
        InferenceResult.Failure.NotConfigured("no inference engine configured on this platform")
}
