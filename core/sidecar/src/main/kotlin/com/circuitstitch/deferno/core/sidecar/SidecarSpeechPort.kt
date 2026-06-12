package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The **Speech capability port** (#119, ADR-0024): a typed facade over [SidecarClient] for the
 * `speech.transcribe` capability — introspect [readiness] and subscribe to the Helper's on-device
 * dictation [transcripts]. The wire mechanics (method ids, [SidecarJson], `JsonElement` payloads) stay
 * inside this port, like its siblings ([SidecarNotificationPort], [SidecarStatusItemPort],
 * [SidecarHotkeyPort]); the consumer (core/speech's `SidecarSpeechToText`) sees only the typed surface
 * and condenses [TranscriptWire] to its domain event at the edge (ADR-0011).
 *
 * Unlike its siblings this port is an interface: it has an out-of-module consumer with its own unit
 * tests, which fake this seam — the same [SidecarClient]/[DefaultSidecarClient] split one level down.
 *
 * **Privacy (ADR-0009/0018):** [transcripts] carries [[Transcript]] text — never logged here; the
 * [TranscriptWire] events redact it from `toString` themselves.
 */
interface SidecarSpeechPort {

    /**
     * The Helper's genuine ready signal — **introspection only, never prompts** (the first real
     * [transcripts] subscribe is what fires the OS Speech/mic prompts, the Helper's `ensureAuthorized`).
     */
    suspend fun readiness(): SidecarSpeechReadiness

    /**
     * Subscribe to on-device dictation ([SidecarMethods.SubscribeTranscript]): each decodable
     * [TranscriptWire] event until the Helper ends the stream. An **undecodable event is dropped, not
     * thrown** (tolerant reader, ADR-0005) — a newer Helper's event type must not kill dictation
     * mid-utterance. Fails with a [SidecarException] when no Helper is bound, the handshake is refused,
     * or the connection drops; cancelling the collector stops the Helper (releases its mic).
     */
    fun transcripts(): Flow<TranscriptWire>
}

/** What [SidecarSpeechPort.readiness] found — three genuinely different states (ADR-0024). */
enum class SidecarSpeechReadiness {
    /** Connected, `speech.transcribe` advertised, and the Speech TCC permission isn't foreclosed. */
    Ready,

    /**
     * No Helper is bound at the well-known socket — the **permanent, normal state** on Linux/Windows
     * and on a Mac without the Helper installed: an absent fast path, not a transient init.
     */
    NoHelper,

    /**
     * A Helper is reachable but can't transcribe now: the handshake was refused, the capability isn't
     * advertised (the Helper couldn't construct its recognizer), or Speech TCC is denied/restricted.
     */
    NotReady,
}

/**
 * The production [SidecarSpeechPort] over a [SidecarClient]. [readiness] degrades, never throws —
 * every [SidecarException] maps to a non-[Ready][SidecarSpeechReadiness.Ready] state so the engine's
 * availability check can't crash the selector.
 */
class DefaultSidecarSpeechPort(private val client: SidecarClient) : SidecarSpeechPort {

    override suspend fun readiness(): SidecarSpeechReadiness = try {
        client.connect()
        when {
            SidecarCapabilities.SpeechTranscribe !in client.capabilities() -> SidecarSpeechReadiness.NotReady
            speechPermissionBlocked() -> SidecarSpeechReadiness.NotReady
            else -> SidecarSpeechReadiness.Ready
        }
    } catch (_: SidecarUnavailableException) {
        SidecarSpeechReadiness.NoHelper
    } catch (_: SidecarException) {
        SidecarSpeechReadiness.NotReady
    }

    override fun transcripts(): Flow<TranscriptWire> =
        client.openStream(SidecarMethods.SubscribeTranscript).mapNotNull { event ->
            // Tolerant at the seam (ADR-0005): an event this client can't read — e.g. a newer Helper's
            // event type — is dropped, never thrown at the dictation collector.
            runCatching { SidecarJson.decodeFromJsonElement(TranscriptWire.serializer(), event) }.getOrNull()
        }

    /**
     * Whether a TCC state forecloses recognition — **Speech or mic** (#120 widened this from Speech
     * alone: a mic-denied Helper can't dictate either, and reporting NotReady is what keeps the
     * selector on the whisper floor). Only `denied`/`restricted` block — `not_determined`/`unknown`
     * (including a reply this client can't read) stay selectable so the engine's permission preflight
     * (or the first real subscribe) can fire the OS prompt. Skipped when the Helper doesn't broker
     * permissions. Introspection only — never prompts. A *failed* query stays a thrown
     * [SidecarException] (→ NotReady in [readiness]'s catch) — deliberately stricter than
     * [DefaultSidecarPermissionPort]'s degrade-to-unknown: a Helper that can't even answer a query is
     * not ready, whereas the UX port must never crash a settings click.
     */
    private suspend fun speechPermissionBlocked(): Boolean {
        if (SidecarCapabilities.Permissions !in client.capabilities()) return false
        return listOf(SidecarPermissionCapabilities.Speech, SidecarPermissionCapabilities.Microphone)
            .any { capability ->
                val result = client.request(
                    SidecarMethods.QueryPermission,
                    SidecarJson.encodeToJsonElement(QueryPermissionWire.serializer(), QueryPermissionWire(capability)),
                ) ?: return@any false
                val status = runCatching {
                    SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), result).status
                }.getOrDefault(PermissionStatusValue.UNKNOWN) // an unreadable reply ≈ unknown: stay selectable
                status == PermissionStatusValue.DENIED || status == PermissionStatusValue.RESTRICTED
            }
    }
}
