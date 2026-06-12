package com.circuitstitch.deferno.core.agent

import kotlinx.serialization.KSerializer

/**
 * Capability port for the [[Agent]]'s inference (ADR-0027): a typed [InferenceRequest] — prompt +
 * kotlinx-serializable result schema — in, a validated instance of that schema out. This is the seam
 * the propose-only Agent services (the [[Extractor]], the [[Plan proposal]] producer) call; which
 * concrete engine sits behind it (the Deferno relay, a dev-key direct Anthropic engine, a local
 * engine later) is configuration, never the caller's concern.
 *
 * **Propose-only (ADR-0027):** an engine maps context to a proposal. It holds no tools and no write
 * access; nothing it returns is committed until the person accepts it through the ordinary Command
 * path.
 *
 * **Privacy invariant (ADR-0009 / ADR-0027):** a request's prompt is the person's own context
 * (transcripts, task titles) and the result is derived from it — implementations must never log,
 * persist, or analytics-report either. An off-device engine runs only by explicit opt-in, enforced
 * *upstream* by the engine-choice [[App setting]] and its gate (#150): only an engine the person
 * configured is ever bound behind this seam.
 *
 * Every failure mode is a typed [InferenceResult.Failure] — malformed model output, transport
 * trouble, or a missing credential never surface as an unhandled crash.
 */
interface InferenceEngine {
    /** Run one inference: [request]'s prompt against its schema, returning a validated [T] or a typed failure. */
    suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T>
}

/**
 * One typed inference call: the instruction/context pair plus the [schema] the model's output must
 * validate against. Deliberately minimal (the walking skeleton, #147) — sampling parameters and
 * multi-turn shapes can grow here when a service needs them.
 */
class InferenceRequest<T : Any>(
    /** The service's standing instructions (ships with the app, ADR-0027) — the system prompt. */
    val instructions: String,
    /** The person's own context for this call (e.g. a [[Brain dump]] transcript) — the user message. */
    val content: String,
    /** The typed result contract the model output is validated against. */
    val schema: InferenceSchema<T>,
)

/**
 * The serializable result contract of an [InferenceRequest]: the [serializer] both derives the JSON
 * schema sent to the model and validates/decodes what comes back — one source of truth, so the
 * decoded [T] can't drift from the schema the model was shown.
 */
class InferenceSchema<T : Any>(
    val serializer: KSerializer<T>,
) {
    /**
     * The structure id surfaced to the model: the schema type's serial name (set it with
     * `@SerialName`), trimmed of any package prefix — derived from the [serializer] rather than
     * passed alongside it, so the id can never disagree with the type.
     */
    val name: String = serializer.descriptor.serialName.substringAfterLast('.')
}
