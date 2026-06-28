package com.circuitstitch.deferno.core.agent

/**
 * Whether the currently-selected inference engine can run **general-purpose** classification over an
 * arbitrary schema (Deferno#525) — the availability gate the Breakdown surface checks before it opens, the
 * cross-platform analogue of iOS's `AppleIntelligence.isAvailable`.
 *
 * Not every registered engine is general-purpose: the on-device deterministic **floor**
 * ([InferenceEngineId.OnDeviceFloor]) only understands the brain-dump extractor prompt and answers
 * `MalformedOutput` for anything else, so it does NOT count here; [InferenceEngineId.Off] is no engine at
 * all. A cloud engine counts only when actually reachable ([InferenceEngineCatalog.credential] non-null —
 * i.e. selected *and* entitled). Any other selected id — a general-purpose on-device LLM (iOS Foundation
 * Models, a future Android runtime) — counts (`else`), so the gate lights up automatically when one lands.
 */
suspend fun InferenceEngineCatalog.hasGeneralPurposeEngine(): Boolean =
    when (selected()) {
        InferenceEngineId.Off, InferenceEngineId.OnDeviceFloor -> false
        InferenceEngineId.DefernoCloud -> credential() != null
        else -> true
    }
