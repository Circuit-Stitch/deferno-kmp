package com.circuitstitch.deferno.ios.agent

import com.circuitstitch.deferno.core.agent.InferenceEngine
import com.circuitstitch.deferno.core.agent.InferenceRequest
import com.circuitstitch.deferno.core.agent.InferenceResult
import com.circuitstitch.deferno.core.agent.jsonSkeleton
import com.circuitstitch.deferno.core.agent.parse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The **in-process inference port** the iOS Swift app implements (#269, ADR-0037) — the iOS twin of the
 * macOS [com.circuitstitch.deferno.macos.agent.NativeInference] seam over Apple Intelligence's Foundation
 * Models (`LanguageModelSession`). Swift calls the on-device model directly, and only **text** crosses the
 * seam — the model runs fully on-device, so the person's transcript never leaves the phone (ADR-0009/0027).
 *
 * Kotlin keeps ownership of the schema and validation: Swift returns the model's raw JSON text (or a non-PII
 * error category), and [NativeInferenceEngine] decodes it against the request's schema. So the propose-only
 * contract and the typed [InferenceResult] failure modes are unchanged at this seam.
 */
interface NativeInference {
    /** Apple Intelligence is downloaded and ready on this device now (`SystemLanguageModel.availability`). */
    fun isAvailable(): Boolean

    /**
     * Run one inference. Swift runs a `LanguageModelSession` over [instructions] + [content], steered to emit
     * only JSON matching [schemaShape] (a by-example skeleton of the [schemaName] structure — exact key names
     * + types, so a small model doesn't guess them), and calls back exactly once: [onJson] with the model's
     * JSON text, or [onError] with a non-PII reason category (never the output).
     */
    fun infer(
        instructions: String,
        content: String,
        schemaName: String,
        schemaShape: String,
        onJson: (String) -> Unit,
        onError: (String) -> Unit,
    )
}

/**
 * Adapts a Swift [NativeInference] to the shared [InferenceEngine] seam (ADR-0027) the Agent services call.
 * Availability is checked up front (so a device without Apple Intelligence answers
 * [InferenceResult.Failure.NotConfigured] without running the model — the Brain dump then salvages); the
 * model's raw text is decoded and validated by [parse], turning malformed output into the typed failure
 * rather than a crash. Installed into the DI graph's [com.circuitstitch.deferno.core.agent.IosOnDeviceInference]
 * forwarder at app launch.
 */
class NativeInferenceEngine(private val native: NativeInference) : InferenceEngine {
    override suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T> {
        if (!native.isAvailable()) {
            return InferenceResult.Failure.NotConfigured("Apple Intelligence is unavailable on this device")
        }
        val outcome = suspendCancellableCoroutine { cont ->
            native.infer(
                instructions = request.instructions,
                content = request.content,
                schemaName = request.schema.name,
                schemaShape = request.schema.jsonSkeleton(),
                onJson = { if (cont.isActive) cont.resume(NativeOutcome.Json(it)) },
                onError = { if (cont.isActive) cont.resume(NativeOutcome.Error(it)) },
            )
        }
        return when (outcome) {
            is NativeOutcome.Json -> request.schema.parse(outcome.text)
            // A generation failure on a *local* engine has no network analogue; Transport is the closest
            // typed bucket (the engine answered with an error), and the reason is non-PII.
            is NativeOutcome.Error -> InferenceResult.Failure.Transport(outcome.reason)
        }
    }
}

private sealed interface NativeOutcome {
    data class Json(val text: String) : NativeOutcome
    data class Error(val reason: String) : NativeOutcome
}
