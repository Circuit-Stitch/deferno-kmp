package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.scopes.AppScope
import kotlin.concurrent.Volatile
import me.tatarka.inject.annotations.IntoMap
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The stable seam the macOS DI graph routes [InferenceEngineId.OnDeviceFoundationModels] to (ADR-0029
 * Phase 3, ADR-0037) — the macOS twin of iOS's `IosOnDeviceInference`.
 *
 * The real engine is a **Swift Foundation Models adapter created at app launch**, so it can't be a
 * compile-time `@IntoMap` entry: the graph binds this stable forwarder and the app [install]s the
 * runtime engine into it once at startup ([com.circuitstitch.deferno.macos.DefernoRoot]). Until
 * installed (a unit host) — or on a Mac without Apple Intelligence (the adapter answers
 * [InferenceResult.Failure.NotConfigured]) — this forwards to [NotConfiguredInferenceEngine]: a typed
 * failure a caller salvages from, never a silent nothing. Nothing on macOS reads the routed seam yet —
 * the twin of iOS's Brain-dump pipeline is still missing (#368), and today's Extractor panel is a dev
 * surface holding the engine directly — so this is the graph wired ahead of its first product caller.
 *
 * ponytail: a process-global delegate — there is exactly one macOS app per process, set once before any
 * inference runs; a per-instance holder would only matter for multiple graphs in one process.
 */
object MacosOnDeviceInference : InferenceEngine {
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
 * macOS AppScope inference bindings (ADR-0029 Phase 3, #150/#269). Like the other platforms this is a
 * process-singleton **device capability**, identity-independent (ADR-0014) — the relay's per-[[Account]]
 * entitlement is enforced server-side, not by a graph scope.
 *
 * **Why this file binds the router itself.** The shared `AgentBindings` (where [RoutingInferenceEngine]
 * is bound on every other target) lives in `src/hosted/kotlin`, which core/agent's build file adds to
 * androidMain/jvmMain/iosMain **only** — Koog publishes no macosArm64 klib, so the hosted source set
 * (and with it the router binding) simply isn't compiled here. macOS therefore seeds and binds its own
 * router below rather than contributing an extra `@IntoMap` entry to someone else's: a second AppScope
 * `InferenceEngine` provider would be a duplicate binding KSP rejects.
 *
 * **What macOS can actually run.** Only the on-device Apple Foundation Models entry — there is no cloud
 * engine on this target, because `KoogInferenceEngine` doesn't compile here. The cloud *descriptor* still
 * reaches the catalog from the shared commonMain `InferenceEngineBindings`, so the Settings row can show
 * the relay as a disabled premium upsell; selecting it finds no `@IntoMap` entry and the router falls
 * through to [NotConfiguredInferenceEngine] — no network call, ever (ADR-0009 / ADR-0027).
 *
 * The registered id matches the Apple-wide default `AppleInferenceEngineBindings` persists
 * ([InferenceEngineId.OnDeviceFoundationModels]), so a fresh Mac routes to Foundation Models with no
 * setting touched.
 */
@ContributesTo(AppScope::class)
interface MacosAgentBindings {
    /** The app-facing seam: route each call to the selected engine, or NotConfigured (ADR-0027, #150). */
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEngine(
        engines: Map<InferenceEngineId, InferenceEngine>,
        catalog: InferenceEngineCatalog,
    ): InferenceEngine = RoutingInferenceEngine(engines, catalog)

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
        InferenceEngineId.OnDeviceFoundationModels to MacosOnDeviceInference
}
