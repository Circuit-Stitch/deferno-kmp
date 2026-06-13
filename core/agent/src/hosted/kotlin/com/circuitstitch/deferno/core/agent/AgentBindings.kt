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
 * exposes the seam; the engine catalog + entitlement gate that drive the endpoint live in
 * `InferenceEngineBindings` (#150), mirroring `SpeechBindings`.
 */
@ContributesTo(AppScope::class)
interface AgentBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEngine(endpoint: AnthropicEndpoint): InferenceEngine = KoogInferenceEngine(endpoint)

    /**
     * The settings-driven cloud endpoint (#150): it points at the relay base URL the
     * [InferenceEngineCatalog] resolves from the environment, and reads its credential through the catalog —
     * which yields a credential only when the **cloud relay is the selected engine** and the Account is
     * **entitled**, otherwise `null`. So every call answers [InferenceResult.Failure.NotConfigured]
     * **without any network request** until that gate is open (ADR-0009 / ADR-0027). The catalog is read
     * per call, so changing the selection or the entitlement takes effect with no restart (#150 AC4).
     */
    @Provides
    fun anthropicEndpoint(catalog: InferenceEngineCatalog): AnthropicEndpoint =
        AnthropicEndpoint(baseUrl = catalog.relayBaseUrl, credentials = { catalog.credential() })
}
