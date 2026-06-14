package com.circuitstitch.deferno.core.agent

/**
 * The app-facing [InferenceEngine] (ADR-0027): routes each call to the engine the person selected, read
 * per call from the [InferenceEngineCatalog] so a just-changed selection takes effect with no restart
 * (#150 AC4). The direct analogue of `SpeechToTextSelector`, minus the rank-fallback — agent engine
 * choice is **explicit**, not best-available: the selected engine acts, or nothing does.
 *
 * Selecting nothing ([InferenceEngineId.Off], the default) or an engine not registered on this device
 * routes to [NotConfiguredInferenceEngine] — a typed [InferenceResult.Failure.NotConfigured] with **no
 * network call** (the privacy invariant). A cloud engine still self-gates on its credential, so a
 * cloud selection the Account isn't entitled to also answers NotConfigured without reaching the wire.
 *
 * The registered [engines] are a `Map<InferenceEngineId, InferenceEngine>` multibinding: each platform
 * contributes the engines it can actually run (`@IntoMap`) — the cloud relay on Android/JVM/iOS, the
 * on-device floor on Android. Bound at AppScope, identity-independent like speech (ADR-0014).
 */
class RoutingInferenceEngine(
    private val engines: Map<InferenceEngineId, InferenceEngine>,
    private val catalog: InferenceEngineCatalog,
) : InferenceEngine {
    override suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T> =
        (engines[catalog.selected()] ?: NotConfiguredInferenceEngine).infer(request)
}
