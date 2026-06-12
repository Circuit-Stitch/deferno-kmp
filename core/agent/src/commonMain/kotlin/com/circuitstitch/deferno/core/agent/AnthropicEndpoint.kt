package com.circuitstitch.deferno.core.agent

/**
 * Where the Anthropic-format engine points (ADR-0027): the Anthropic API itself (a developer's own
 * key, today) or the Deferno-operated relay (same wire format, PAT-authenticated, per-[[Account]]
 * entitlement) when it lands. Pointing elsewhere is a *configuration* change, never a wire-format
 * one — that equivalence is the whole relay design.
 *
 * Engine choice and the off-device opt-in are #150's [[App setting]]; this is only the coordinate
 * pair the bound engine reads per call.
 */
class AnthropicEndpoint(
    /** Base URL of the Anthropic-format endpoint. */
    val baseUrl: String = ANTHROPIC_API_BASE_URL,
    /** Supplies the credential per call — a dev API key today, the PAT against the relay later. */
    val credentials: InferenceCredentialProvider = InferenceCredentialProvider.Unconfigured,
) {
    companion object {
        const val ANTHROPIC_API_BASE_URL: String = "https://api.anthropic.com"
    }
}

/**
 * Per-call credential source for an [InferenceEngine]'s endpoint. Suspend so a real provider can
 * read the secure vault (ADR-0009) without blocking; `null` means *no credential configured*, which
 * an engine must surface as [InferenceResult.Failure.NotConfigured] **without making any network
 * call** — never as a crash or an anonymous request.
 */
fun interface InferenceCredentialProvider {
    suspend fun credential(): String?

    companion object {
        /** The not-set-up default (until #150's settings land): every call is [InferenceResult.Failure.NotConfigured]. */
        val Unconfigured: InferenceCredentialProvider = InferenceCredentialProvider { null }
    }
}
