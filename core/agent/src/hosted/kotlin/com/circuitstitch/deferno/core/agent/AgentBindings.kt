package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.IntoMap
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The app-facing [InferenceEngine] bindings (ADR-0027), AppScope process-singletons: like speech, a
 * **device capability, identity-independent** — bound at AppScope, not AccountScope (ADR-0014). The
 * relay's per-[[Account]] entitlement is the relay's concern, enforced server-side, not a graph scope.
 *
 * Compiled into Android/JVM/iOS (the `hosted` source dir) — the targets that run Koog. The seam itself
 * is the [RoutingInferenceEngine] over the `Map<InferenceEngineId, InferenceEngine>` multibinding; the
 * Koog cloud engine is one `@IntoMap` entry (Android adds the on-device floor). macOS has neither (no
 * Koog klib) and binds [NotConfiguredInferenceEngine] directly (`MacosAgentBindings`).
 */
@ContributesTo(AppScope::class)
interface AgentBindings {
    /** The app-facing seam: route each call to the selected engine, or NotConfigured (ADR-0027, #150). */
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEngine(
        engines: Map<InferenceEngineId, InferenceEngine>,
        catalog: InferenceEngineCatalog,
    ): InferenceEngine = RoutingInferenceEngine(engines, catalog)

    /** The Anthropic-format cloud relay engine, keyed by its id for the router to select. */
    @Provides
    @IntoMap
    @SingleIn(AppScope::class)
    fun cloudInferenceEngine(endpoint: AnthropicEndpoint): Pair<InferenceEngineId, InferenceEngine> =
        InferenceEngineId.DefernoCloud to KoogInferenceEngine(endpoint)

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
