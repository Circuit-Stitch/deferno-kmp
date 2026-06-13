package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * macOS AppScope inference binding (ADR-0029). Koog ships no macosArm64 klib, so the hosted
 * `KoogInferenceEngine` (and the shared `AgentBindings`) aren't compiled for this target; the floor
 * is [NotConfiguredInferenceEngine] until the Swift FoundationModels engine is injected at the Kotlin
 * seam (Phase 3). The relay's per-[[Account]] entitlement is server-side, not a graph scope — so like
 * the other platforms this is an AppScope process-singleton, identity-independent (ADR-0014).
 *
 * Off-device inference stays impossible without an explicitly configured engine (ADR-0009 /
 * ADR-0027): the floor never reaches the network.
 */
@ContributesTo(AppScope::class)
interface MacosAgentBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun inferenceEngine(): InferenceEngine = NotConfiguredInferenceEngine
}
