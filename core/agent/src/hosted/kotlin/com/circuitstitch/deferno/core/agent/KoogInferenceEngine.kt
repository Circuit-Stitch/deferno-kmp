package com.circuitstitch.deferno.core.agent

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Anthropic-format [InferenceEngine] (ADR-0027): Koog 1.0's stock [AnthropicLLMClient] pointed
 * at [AnthropicEndpoint.baseUrl] — the Anthropic API under a developer's own key today, the
 * Deferno-operated relay (same wire format) later. This class is the module's entire Koog boundary:
 * no Koog type reaches the seam, so callers and tests stand on [InferenceEngine] alone.
 *
 * Structured output is native (the request carries the schema derived from the request's
 * [InferenceSchema.serializer]; the built-in Anthropic models declare the JSON-schema capability).
 * When the model's output still fails to parse, Koog's [StructureFixingParser] repairs it with up to
 * [repairRetries] follow-up calls through the same [model]; output that defeats repair too surfaces
 * as [InferenceResult.Failure.MalformedOutput] — never an exception (#147).
 *
 * @param model One of Koog's built-in `AnthropicModels` constants. A hand-rolled [LLModel] needs an
 *   entry in [AnthropicClientSettings.modelVersionsMap] (the client resolves the wire id through it
 *   and throws for unknown models), so custom ids are out of the skeleton's scope.
 * @param baseHttpClient The Ktor client Koog's per-call clients derive from — the tests' MockEngine
 *   seam. The default discovers the per-target engine (OkHttp / Darwin) at runtime.
 */
class KoogInferenceEngine(
    private val endpoint: AnthropicEndpoint,
    private val model: LLModel = AnthropicModels.Haiku_4_5,
    private val repairRetries: Int = DEFAULT_REPAIR_RETRIES,
    private val baseHttpClient: HttpClient = HttpClient(),
) : InferenceEngine {

    /**
     * The executor for the credential it was built with. Koog bakes the credential into the client's
     * default headers at construction, so a credential change (rare — sign-in, key rotation) means a
     * fresh client; caching by credential keeps the steady state at one Ktor client instead of one
     * per call.
     */
    private var cached: Pair<String, PromptExecutor>? = null
    private val cacheLock = Mutex()

    override suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T> {
        val credential = endpoint.credentials.credential()
            ?: return InferenceResult.Failure.NotConfigured("no inference credential configured")

        val outcome = try {
            executorFor(credential).executeStructured(
                prompt = prompt(request.schema.name) {
                    system(request.instructions)
                    user(request.content)
                },
                model = model,
                serializer = request.schema.serializer,
                fixingParser = if (repairRetries > 0) {
                    StructureFixingParser(model = model, retries = repairRetries)
                } else {
                    null
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Koog runs only the *parsing* inside the Result; the wire call itself throws
            // (LLMClientException on HTTP/protocol errors, engine exceptions on no-network).
            return InferenceResult.Failure.Transport(e.transportDetail())
        }

        return outcome.fold(
            onSuccess = { InferenceResult.Success(it.data) },
            onFailure = { failure ->
                when (failure) {
                    // Result.runCatching swallows cancellation; resurface it.
                    is CancellationException -> throw failure
                    // A repair call died on the wire mid-parse.
                    is LLMClientException -> InferenceResult.Failure.Transport(failure.transportDetail())
                    // SerializationException / LLMStructuredParsingError: class name ONLY — their
                    // messages quote the malformed model output, which must never leak into a
                    // loggable detail (the privacy invariant on InferenceEngine).
                    else -> InferenceResult.Failure.MalformedOutput(
                        failure::class.simpleName ?: "structured output parse failure",
                    )
                }
            },
        )
    }

    private suspend fun executorFor(credential: String): PromptExecutor = cacheLock.withLock {
        cached?.takeIf { it.first == credential }?.second
            ?: MultiLLMPromptExecutor(
                AnthropicLLMClient(
                    apiKey = credential,
                    settings = AnthropicClientSettings(baseUrl = endpoint.baseUrl),
                    httpClientFactory = KtorKoogHttpClient.Factory(baseClient = baseHttpClient),
                ),
            ).also { cached = credential to it }
    }

    /** Wire-level diagnostics carry no prompt text or model output, so class + message are loggable. */
    private fun Exception.transportDetail(): String =
        "${this::class.simpleName}: $message"

    companion object {
        /**
         * One repair attempt by default: a second same-model call on a parse failure is the cheap
         * insurance the #147 acceptance asks for; services that would rather fail fast pass 0.
         */
        const val DEFAULT_REPAIR_RETRIES: Int = 1
    }
}
