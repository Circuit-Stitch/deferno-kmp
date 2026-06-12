package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The **Speech capability port** (#119, ADR-0024): a typed facade over [SidecarClient] for the
 * `speech.transcribe` capability — introspect [readiness], resolve the dictation permission gates with
 * the prompting [preflight] (#120/#172), and subscribe to the Helper's on-device dictation
 * [transcripts]. The wire mechanics (method ids, [SidecarJson], `JsonElement` payloads) **and the TCC
 * permission vocabulary** stay inside this port (#172): a consumer sees which dictation gate blocks
 * ([SidecarDictationPermission]), never a raw [PermissionStatusValue]. The consumer (core/speech's
 * `SidecarSpeechToText`) condenses [TranscriptWire] to its domain event at the edge (ADR-0011).
 *
 * Unlike its siblings ([SidecarNotificationPort], [SidecarStatusItemPort], [SidecarHotkeyPort]) this
 * port is an interface: it has an out-of-module consumer with its own unit tests, which fake this
 * seam — the same [SidecarClient]/[DefaultSidecarClient] split one level down.
 *
 * **Privacy (ADR-0009/0018):** [transcripts] carries [[Transcript]] text — never logged here; the
 * [TranscriptWire] events redact it from `toString` themselves.
 */
interface SidecarSpeechPort {

    /**
     * The Helper's genuine ready signal — **introspection only, never prompts** — and, when not
     * [Ready][SidecarSpeechReadiness.Ready], *why* (#172). Resolving an undetermined gate is
     * [preflight]'s job.
     */
    suspend fun readiness(): SidecarSpeechReadiness

    /**
     * The #120 permission **preflight**, owned by the seam (#172): resolve the dictation gates —
     * Speech first, then Microphone, the same order the Helper's own first-use `ensureAuthorized`
     * prompts in (`contracts/sidecar/protocol-v1.md`) — firing the real OS prompt on a
     * `not_determined` gate. Only a settled denied/restricted blocks, and the result names **which**
     * gate; `unknown` (no Helper, a Helper that doesn't broker permissions, an unreadable reply)
     * stays [Clear][SpeechPreflight.Clear] so the first real subscribe can still prompt. Degrades,
     * never throws.
     */
    suspend fun preflight(): SpeechPreflight

    /**
     * Subscribe to on-device dictation ([SidecarMethods.SubscribeTranscript]): each decodable
     * [TranscriptWire] event until the Helper ends the stream. An **undecodable event is dropped, not
     * thrown** (tolerant reader, ADR-0005) — a newer Helper's event type must not kill dictation
     * mid-utterance. Fails with a [SidecarException] when no Helper is bound, the handshake is refused,
     * or the connection drops; cancelling the collector stops the Helper (releases its mic).
     */
    fun transcripts(): Flow<TranscriptWire>
}

/** What [SidecarSpeechPort.readiness] found — and, when not ready, **why** (ADR-0024, #172). */
sealed interface SidecarSpeechReadiness {

    /** Connected, `speech.transcribe` advertised, and no dictation gate is foreclosed. */
    data object Ready : SidecarSpeechReadiness

    /**
     * No Helper is bound at the well-known socket — the **permanent, normal state** on Linux/Windows
     * and on a Mac without the Helper installed: an absent fast path, not a transient init.
     */
    data object NoHelper : SidecarSpeechReadiness

    /**
     * A Helper is reachable but didn't advertise `speech.transcribe` in its `welcome` — it couldn't
     * construct its on-device recognizer.
     */
    data object CapabilityMissing : SidecarSpeechReadiness

    /** A dictation gate is foreclosed (denied/restricted TCC) — [permission] names which. */
    data class PermissionBlocked(val permission: SidecarDictationPermission) : SidecarSpeechReadiness

    /** A Helper is bound but unusable: the handshake was refused, or it failed to answer a query. */
    data object NotResponding : SidecarSpeechReadiness
}

/**
 * The two OS permission gates [[Dictation]] needs, named OS-agnostically (#172 — the TCC enum values
 * stay behind the port). Declaration order is the Helper's prompt order: Speech, then Microphone.
 */
enum class SidecarDictationPermission { Speech, Microphone }

/** What [SidecarSpeechPort.preflight] settled to. */
sealed interface SpeechPreflight {

    /** Every gate granted or still open — the Helper's mic may engage. */
    data object Clear : SpeechPreflight

    /** A gate settled denied/restricted — dictation is foreclosed and [permission] names which. */
    data class Blocked(val permission: SidecarDictationPermission) : SpeechPreflight
}

/**
 * The production [SidecarSpeechPort] over a [SidecarClient]. [readiness] and [preflight] degrade,
 * never throw — every [SidecarException] maps to a non-[Ready][SidecarSpeechReadiness.Ready] state so
 * the engine's availability check can't crash the selector. [preflight] prompts through the
 * [SidecarPermissionPort] (the #120 broker, shared with the Settings UX), so the discrete
 * `not_determined → request → settled` flow has exactly one wire implementation.
 */
class DefaultSidecarSpeechPort(
    private val client: SidecarClient,
    private val permissions: SidecarPermissionPort,
) : SidecarSpeechPort {

    override suspend fun readiness(): SidecarSpeechReadiness = try {
        client.connect()
        when {
            SidecarCapabilities.SpeechTranscribe !in client.capabilities() ->
                SidecarSpeechReadiness.CapabilityMissing
            else -> when (val blocked = blockedGate()) {
                null -> SidecarSpeechReadiness.Ready
                else -> SidecarSpeechReadiness.PermissionBlocked(blocked)
            }
        }
    } catch (_: SidecarUnavailableException) {
        SidecarSpeechReadiness.NoHelper
    } catch (_: SidecarException) {
        SidecarSpeechReadiness.NotResponding
    }

    override suspend fun preflight(): SpeechPreflight {
        DICTATION_GATES.forEach { (capability, gate) ->
            val settled = when (val now = permissions.status(capability)) {
                PermissionStatusValue.NOT_DETERMINED -> permissions.request(capability)
                else -> now
            }
            if (settled == PermissionStatusValue.DENIED || settled == PermissionStatusValue.RESTRICTED) {
                return SpeechPreflight.Blocked(gate)
            }
        }
        return SpeechPreflight.Clear
    }

    override fun transcripts(): Flow<TranscriptWire> =
        client.openStream(SidecarMethods.SubscribeTranscript).mapNotNull { event ->
            // Tolerant at the seam (ADR-0005): an event this client can't read — e.g. a newer Helper's
            // event type — is dropped, never thrown at the dictation collector.
            runCatching { SidecarJson.decodeFromJsonElement(TranscriptWire.serializer(), event) }.getOrNull()
        }

    /**
     * The gate a TCC state forecloses, or null — **Speech or mic** (#120 widened this from Speech
     * alone: a mic-denied Helper can't dictate either, and reporting the block is what keeps the
     * selector on the whisper floor). Only `denied`/`restricted` block — `not_determined`/`unknown`
     * (including a reply this client can't read) stay selectable so [preflight] (or the first real
     * subscribe) can fire the OS prompt. Skipped when the Helper doesn't broker permissions.
     * Introspection only — never prompts. A *failed* query stays a thrown [SidecarException] (→
     * [NotResponding][SidecarSpeechReadiness.NotResponding] in [readiness]'s catch) — deliberately
     * stricter than [DefaultSidecarPermissionPort]'s degrade-to-unknown: a Helper that can't even
     * answer a query is not ready, whereas the UX port must never crash a settings click.
     */
    private suspend fun blockedGate(): SidecarDictationPermission? {
        if (SidecarCapabilities.Permissions !in client.capabilities()) return null
        return DICTATION_GATES.firstNotNullOfOrNull { (capability, gate) ->
            val result = client.request(
                SidecarMethods.QueryPermission,
                SidecarJson.encodeToJsonElement(QueryPermissionWire.serializer(), QueryPermissionWire(capability)),
            ) ?: return@firstNotNullOfOrNull null
            val status = runCatching {
                SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), result).status
            }.getOrDefault(PermissionStatusValue.UNKNOWN) // an unreadable reply ≈ unknown: stay selectable
            gate.takeIf { status == PermissionStatusValue.DENIED || status == PermissionStatusValue.RESTRICTED }
        }
    }

    private companion object {
        /** The dictation gates in the Helper's prompt order (`protocol-v1.md`): Speech, then mic. */
        val DICTATION_GATES = listOf(
            SidecarPermissionCapabilities.Speech to SidecarDictationPermission.Speech,
            SidecarPermissionCapabilities.Microphone to SidecarDictationPermission.Microphone,
        )
    }
}
