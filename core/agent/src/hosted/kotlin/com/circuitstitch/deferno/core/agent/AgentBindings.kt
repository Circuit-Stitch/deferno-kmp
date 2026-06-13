package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The app-facing [InferenceEngine] binding (ADR-0027), an AppScope process-singleton: like speech, a
 * **device capability, identity-independent** — bound at AppScope, not AccountScope (ADR-0014). The
 * relay's per-[[Account]] entitlement is the relay's concern, enforced server-side, not a graph
 * scope.
 *
 * `@Provides` over the abstract [InferenceEngine] return type (not the impl) so the merged graph
 * exposes the seam; the engine-catalog multibinding (relay / local engines) arrives with #150's
 * engine choice, mirroring `SpeechBindings`.
 */
@ContributesTo(AppScope::class)
interface AgentBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEngine(endpoint: AnthropicEndpoint): InferenceEngine = KoogInferenceEngine(endpoint)

    /**
     * The endpoint until #150's engine choice + off-device opt-in [[App setting]] land: the
     * Anthropic API base with **no credential**, so every call answers
     * [InferenceResult.Failure.NotConfigured] and nothing off-device can happen silently
     * (ADR-0009 / ADR-0027). #150 replaces this provider with the settings-driven configuration
     * (the relay URL + PAT, or a developer key).
     */
    @Provides
    fun anthropicEndpoint(): AnthropicEndpoint = AnthropicEndpoint()
}
