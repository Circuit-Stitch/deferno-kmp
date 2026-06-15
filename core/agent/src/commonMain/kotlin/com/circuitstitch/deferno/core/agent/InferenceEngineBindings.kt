package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The inference-engine setting/gate bindings (#150, ADR-0027), AppScope process-singletons — the
 * [InferenceEngineCatalog] (over the per-Account [RelayEntitlement] + the device-local
 * [InferenceEnginePreference] each platform supplies) and the [RelayEntitlement] itself. AppScope,
 * identity-independent like speech (ADR-0014).
 *
 * The catalog lists every engine registered on this device: the cloud relay descriptor below, plus any
 * on-device engine each platform contributes `@IntoSet` (Android registers the deterministic floor) —
 * mirroring how the `Set<SpeechToText>` engines feed `SpeechEngineCatalog`. The endpoint binding
 * (`AgentBindings.anthropicEndpoint`) reads the catalog so the bound cloud [InferenceEngine] is shut
 * until the cloud relay is selected and the Account is entitled.
 */
@ContributesTo(AppScope::class)
interface InferenceEngineBindings {
    /** The Deferno cloud-relay descriptor, base URL from the merged-graph [DefernoEnvironment] (#150). */
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun cloudEngineDescriptor(environment: DefernoEnvironment): InferenceEngineDescriptor =
        InferenceEngineDescriptor(
            InferenceEngineId.DefernoCloud,
            InferenceEngineOrigin.DefernoCloud,
            environment.baseUrl,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEngineCatalog(
        engines: Set<InferenceEngineDescriptor>,
        preference: InferenceEnginePreference,
        entitlement: RelayEntitlement,
    ): InferenceEngineCatalog = InferenceEngineCatalog(engines.toList(), preference, entitlement)

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
