package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The inference-engine setting/gate bindings (#150, ADR-0027), AppScope process-singletons — the
 * [InferenceEngineCatalog] (cloud relay base URL from the merged-graph [DefernoEnvironment], over the
 * per-Account [RelayEntitlement] + the device-local [InferenceEnginePreference] each platform supplies)
 * and the [RelayEntitlement] itself. AppScope, identity-independent like speech (ADR-0014).
 *
 * The endpoint binding (`AgentBindings.anthropicEndpoint`) reads the catalog so the bound cloud
 * [InferenceEngine] is shut until a cloud engine is selected and the Account is entitled.
 */
@ContributesTo(AppScope::class)
interface InferenceEngineBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEngineCatalog(
        environment: DefernoEnvironment,
        preference: InferenceEnginePreference,
        entitlement: RelayEntitlement,
    ): InferenceEngineCatalog = InferenceEngineCatalog.forEnvironment(environment, preference, entitlement)

    /**
     * The per-Account relay entitlement until Deferno#345 exposes the real source: a constant fake bound
     * `entitled = false` — the relay isn't deployed, so no Account is entitled, the cloud row renders a
     * disabled "Premium" state, and no inference is attempted (#150 AC2). Swap this provider for the real
     * source (the authed client reading the Active Account's entitlement) when Deferno#345 lands.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun relayEntitlement(): RelayEntitlement = FakeRelayEntitlement(entitled = false)
}
