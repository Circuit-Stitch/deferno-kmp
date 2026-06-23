package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.scopes.AppScope
import kotlin.concurrent.Volatile
import me.tatarka.inject.annotations.IntoMap
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The stable seam the iOS DI graph routes [InferenceEngineId.OnDeviceFoundationModels] to (#269, ADR-0037).
 *
 * The real engine is a **Swift Foundation Models adapter created at app launch**, so — unlike the Android
 * deterministic floor — it can't be a compile-time `@IntoMap` entry. Instead the graph binds this stable
 * forwarder and the app [install]s the runtime engine into it once at startup ([com.circuitstitch.deferno.ios.DefernoRoot]).
 * Until installed (a unit host) — or on a device without Apple Intelligence (the adapter answers
 * [InferenceResult.Failure.NotConfigured]) — this forwards to [NotConfiguredInferenceEngine], so the Brain
 * dump pipeline salvages rather than silently producing nothing (ADR-0037).
 *
 * ponytail: a process-global delegate — there is exactly one iOS app per process, set once before any
 * inference runs; a per-instance holder would only matter for multiple graphs in one process (never on iOS).
 */
object IosOnDeviceInference : InferenceEngine {
    @Volatile
    private var delegate: InferenceEngine? = null

    /** Install the runtime engine (the Swift Foundation Models adapter); called once at app launch. */
    fun install(engine: InferenceEngine) {
        delegate = engine
    }

    override suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T> =
        (delegate ?: NotConfiguredInferenceEngine).infer(request)
}

/**
 * iOS Apple Foundation Models bindings (#269, ADR-0037): register the on-device system-language-model engine
 * as a selectable catalog engine (`@IntoSet` descriptor — OnDevice, so ungated) and as the routable engine
 * the [RoutingInferenceEngine] runs when it is the selection (`@IntoMap`, the iOS default). iOS-only (this
 * `src/iosMain` source set, not the appleMain shared with macOS) — macOS keeps its own NotConfigured floor
 * (`MacosAgentBindings`) and routes Foundation Models through a separate dev bridge (ADR-0029).
 */
@ContributesTo(AppScope::class)
interface IosFoundationModelsBindings {
    /** Apple Foundation Models as a selectable on-device catalog engine — OnDevice origin → ungated. */
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun foundationModelsDescriptor(): InferenceEngineDescriptor =
        InferenceEngineDescriptor(InferenceEngineId.OnDeviceFoundationModels, InferenceEngineOrigin.OnDevice)

    /** Route the OnDeviceFoundationModels selection to the forwarder the app installs the Swift engine into. */
    @Provides
    @IntoMap
    @SingleIn(AppScope::class)
    fun foundationModelsEngine(): Pair<InferenceEngineId, InferenceEngine> =
        InferenceEngineId.OnDeviceFoundationModels to IosOnDeviceInference
}
