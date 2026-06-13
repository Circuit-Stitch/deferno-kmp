package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.network.DefernoEnvironment
import kotlin.jvm.JvmInline

/**
 * A stable identifier for an inference engine the [[Agent]] can run on (#150, ADR-0027) — the value of
 * the device-local **inference-engine** [[App setting]] ([InferenceEnginePreference]). Open (a value
 * class, not an enum) so on-device runtimes and a future BYO engine are just more ids that register, with
 * no type to break (mirrors `SpeechEngineId`).
 */
@JvmInline
value class InferenceEngineId(val value: String) {
    companion object {
        /** The **default**: no engine — the Agent stands down, no inference of any kind (ADR-0027). */
        val Off: InferenceEngineId = InferenceEngineId("off")

        /** The Deferno-operated, Anthropic-format **cloud relay** (PAT-auth, per-Account entitlement). */
        val DefernoCloud: InferenceEngineId = InferenceEngineId("deferno-cloud")
    }
}

/**
 * Where an inference engine runs — the **only** thing the entitlement gate keys on (#150, ADR-0027):
 * [DefernoCloud] engines require the Account's relay entitlement; [OnDevice] engines are ungated and
 * available to everyone. Per-origin, never per-engine and never per-model.
 */
enum class InferenceEngineOrigin {
    /** On this device (desktop Ollama, Android LiteRT, …) — ungated, available to everyone. */
    OnDevice,

    /** The Deferno-operated cloud relay — gated by the Active [[Account]]'s entitlement (premium). */
    DefernoCloud,
}

/**
 * Whether an [InferenceEngineOption] can be selected right now, and why not (#150). v1 has one disabled
 * reason — a cloud engine the Account isn't entitled to ([RequiresPremium]); on-device engines add their
 * own reasons (not-installed, downloading) when they land. Mirrors `SpeechAvailability`.
 */
sealed interface InferenceEngineAvailability {
    /** Selectable now. */
    data object Available : InferenceEngineAvailability

    /** A cloud engine the Active Account isn't entitled to — shown **disabled** as a premium upsell (AC2). */
    data object RequiresPremium : InferenceEngineAvailability
}

/**
 * One selectable engine the Settings "Agent" row offers (#150): an engine [id], its [origin] (which the
 * gate keys on), and its current [availability]. "Off" is **not** an option here — it is the absence of an
 * engine (the default the View always offers above these).
 */
data class InferenceEngineOption(
    val id: InferenceEngineId,
    val origin: InferenceEngineOrigin,
    val availability: InferenceEngineAvailability,
)

/**
 * A registered inference engine in the [InferenceEngineCatalog] — an [id], its [origin], and the [baseUrl]
 * its endpoint points at (blank for an on-device engine that needs none). v1 registers exactly one: the
 * Deferno cloud relay.
 */
data class InferenceEngineDescriptor(
    val id: InferenceEngineId,
    val origin: InferenceEngineOrigin,
    val baseUrl: String = "",
)

/**
 * The device-local **inference-engine choice** + its **cloud gate** (#150, ADR-0027): the single AppScope
 * consult-point both the Settings [[Destination]] and the cloud endpoint read. The Settings row reads
 * [options]/[selected]/[select]; the bound cloud endpoint reads [relayBaseUrl] + [credential]. The direct
 * analogue of `SpeechEngineCatalog`, plus the entitlement gate speech never needs (speech is never cloud).
 *
 * It is an **[[App setting]]**: device-local (the selection persists through [InferenceEnginePreference]),
 * **never synced**, never crossing Accounts (AppScope, identity-independent like speech, ADR-0014). The
 * relay's per-Account entitlement is enforced server-side; the [entitlement] seam reads a fake-able flag.
 *
 * **The gate lives in [credential]**: it yields the relay credential **only** when the selected engine is
 * the cloud relay **and** the Account is entitled — otherwise `null`, so the bound cloud [InferenceEngine]
 * answers [InferenceResult.Failure.NotConfigured] **without any network call** (AC2). Read per call, so a
 * just-changed selection or entitlement takes effect immediately, no restart (AC4). **Measured** (commonTest).
 */
class InferenceEngineCatalog(
    private val engines: List<InferenceEngineDescriptor>,
    private val preference: InferenceEnginePreference,
    private val entitlement: RelayEntitlement,
    // The relay PAT source, read only when the selected engine is cloud AND the Account is entitled.
    // Unconfigured until the relay PAT wiring lands (Deferno#345) — so even an entitled cloud selection
    // answers NotConfigured today rather than reaching a relay that isn't deployed.
    private val relayCredential: InferenceCredentialProvider = InferenceCredentialProvider.Unconfigured,
) {
    /** The cloud relay's base URL the bound endpoint points at, or the Anthropic base when none is registered. */
    val relayBaseUrl: String =
        engines.firstOrNull { it.origin == InferenceEngineOrigin.DefernoCloud }?.baseUrl
            ?.takeIf { it.isNotBlank() }
            ?: AnthropicEndpoint.ANTHROPIC_API_BASE_URL

    /**
     * The selectable engines for the Settings row — each registered engine with its current availability: a
     * [InferenceEngineOrigin.DefernoCloud] engine is [InferenceEngineAvailability.RequiresPremium] until the
     * Account is entitled (shown disabled, AC2), an on-device engine is always [Available]. Empty on a
     * device with no engine registered → the View hides the row (like speech).
     */
    suspend fun options(): List<InferenceEngineOption> {
        val entitled = entitlement.isEntitled()
        return engines.map { engine ->
            val availability = when (engine.origin) {
                InferenceEngineOrigin.DefernoCloud ->
                    if (entitled) InferenceEngineAvailability.Available else InferenceEngineAvailability.RequiresPremium
                InferenceEngineOrigin.OnDevice -> InferenceEngineAvailability.Available
            }
            InferenceEngineOption(engine.id, engine.origin, availability)
        }
    }

    /** The current device-local choice — defaults to [InferenceEngineId.Off] when none is set. */
    fun selected(): InferenceEngineId = preference.selectedEngine()

    /** Persist the device-local choice. **Never** synced to the backend (App setting). */
    fun select(id: InferenceEngineId) = preference.setSelectedEngine(id)

    /**
     * The cloud relay credential, gated by **selection + entitlement** (ADR-0027). `null` unless the
     * selected engine is the cloud relay and the Account is entitled → the cloud engine answers
     * NotConfigured and makes no network call. Read per call → a just-changed selection/entitlement is
     * honoured with no restart. On-device engines never reach this (they are separate engine bindings).
     */
    suspend fun credential(): String? =
        if (selected() == InferenceEngineId.DefernoCloud && entitlement.isEntitled()) {
            relayCredential.credential()
        } else {
            null
        }

    companion object {
        /** The v1 catalog: a single Deferno cloud-relay engine whose base URL comes from [environment]. */
        fun forEnvironment(
            environment: DefernoEnvironment,
            preference: InferenceEnginePreference,
            entitlement: RelayEntitlement,
            relayCredential: InferenceCredentialProvider = InferenceCredentialProvider.Unconfigured,
        ): InferenceEngineCatalog = forRelay(environment.baseUrl, preference, entitlement, relayCredential)

        /**
         * A catalog with a single Deferno cloud-relay engine at [baseUrl] — the [forEnvironment] body, and
         * what the seam tests outside core/agent build (they can't see [DefernoEnvironment], an
         * `implementation` dep).
         */
        fun forRelay(
            baseUrl: String,
            preference: InferenceEnginePreference,
            entitlement: RelayEntitlement,
            relayCredential: InferenceCredentialProvider = InferenceCredentialProvider.Unconfigured,
        ): InferenceEngineCatalog = InferenceEngineCatalog(
            engines = listOf(
                InferenceEngineDescriptor(InferenceEngineId.DefernoCloud, InferenceEngineOrigin.DefernoCloud, baseUrl),
            ),
            preference = preference,
            entitlement = entitlement,
            relayCredential = relayCredential,
        )

        /**
         * The inert, empty catalog: no engine registered, so [options] is empty (the Settings row hides),
         * [selected] is [InferenceEngineId.Off], and [credential] is `null`. The analogue of
         * [EmptySpeechEngineCatalog] — the default the shell/Settings tests build with.
         */
        val Inert: InferenceEngineCatalog = InferenceEngineCatalog(
            engines = emptyList(),
            preference = InMemoryInferenceEnginePreference(),
            entitlement = RelayEntitlement { false },
        )
    }
}
