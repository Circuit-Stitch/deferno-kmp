package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.sidecar.SidecarException
import com.circuitstitch.deferno.core.sidecar.SidecarSecurityException
import com.circuitstitch.deferno.core.sidecar.SidecarSpeechPort
import com.circuitstitch.deferno.core.sidecar.SidecarSpeechReadiness
import com.circuitstitch.deferno.core.sidecar.SidecarUnavailableException
import com.circuitstitch.deferno.core.sidecar.TranscriptWire
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Sidecar-hosted native speech engine (#119, ADR-0024): dictation runs in the per-OS native Helper
 * (SFSpeechRecognizer on macOS, #121) and reaches the JVM through the [SidecarSpeechPort] — the typed
 * capability port that keeps the wire mechanics inside core/sidecar. The seam stays at the
 * **Transcript altitude** (ADR-0018): the Helper owns its own mic + recognizer and emits **text — no
 * PCM ever crosses the socket**; this engine condenses the port's contract-owned [TranscriptWire] to
 * the domain [TranscriptEvent] at the edge (ADR-0011).
 *
 * **Availability is the Helper's ready signal**, not optimism ([SidecarSpeechPort.readiness]):
 * [SpeechAvailability.Available] only when the Helper is connected, advertised `speech.transcribe` in
 * its `welcome` (it only does so when it can genuinely construct the on-device recognizer), and the
 * Speech TCC permission isn't denied/restricted. The two degraded states stay distinct: **no Helper
 * bound** — the permanent, normal state on Linux/Windows — reports
 * [UnavailableReason.NotInstalled] ("not available on this device"), while a present-but-not-ready
 * Helper reports [UnavailableReason.NotReady]; either way the [SpeechToTextSelector] keeps the
 * always-available whisper floor (ADR-0018/0024). A `not_determined` permission stays Available:
 * introspection never prompts — the first real subscribe is what fires the OS prompt.
 *
 * **English-only v1 (ADR-0018):** a non-English locale reports [UnavailableReason.UnsupportedLocale]
 * locally, without dialing.
 *
 * **Privacy (ADR-0009/0018):** events carry [[Transcript]] text — never logged here; failures map to
 * non-PII [SpeechError]s.
 *
 * Measured (jvmTest, ADR-0006): unlike the whisper actuals (mic + model on a real desktop), this engine
 * is pure mapping over the port, exercised end-to-end on the Linux fast path against the stub Helper.
 */
internal class SidecarSpeechToText(
    private val port: SidecarSpeechPort,
) : SpeechToText {

    override val id: SpeechEngineId = SpeechEngineId.Sidecar

    /** Above the whisper floor (0): the native fast path wins whenever the Helper is genuinely ready. */
    override val rank: Int = RANK

    /**
     * SFSpeechRecognizer is a short-utterance recognizer (the on-device request is minutes-bounded), so
     * a [ContinuityHint.Continuous] session prefers the streaming whisper baseline (ADR-0018/0024).
     */
    override val supportsContinuous: Boolean = false

    override suspend fun availability(locale: String): SpeechAvailability = when {
        !locale.isEnglishLocale() -> SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale)
        else -> when (port.readiness()) {
            SidecarSpeechReadiness.Ready -> SpeechAvailability.Available
            SidecarSpeechReadiness.NoHelper -> SpeechAvailability.Unavailable(UnavailableReason.NotInstalled)
            SidecarSpeechReadiness.NotReady -> SpeechAvailability.Unavailable(UnavailableReason.NotReady)
        }
    }

    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> = flow {
        if (!locale.isEnglishLocale()) {
            emit(TranscriptEvent.Error(SpeechError.Unavailable))
            return@flow
        }
        try {
            port.transcripts().collect { emit(it.toEvent()) }
        } catch (e: SidecarException) {
            emit(TranscriptEvent.Error(e.toSpeechError()))
        }
    }

    /** Condense the contract-owned wire event to the domain event at the edge (ADR-0011). */
    private fun TranscriptWire.toEvent(): TranscriptEvent = when (this) {
        is TranscriptWire.Partial -> TranscriptEvent.Partial(text)
        is TranscriptWire.Final -> TranscriptEvent.Final(text)
        is TranscriptWire.Failure -> TranscriptEvent.Error(reason.toSpeechError())
    }

    /** Map the Helper's non-PII failure reasons onto the seam's [SpeechError] buckets. */
    private fun String.toSpeechError(): SpeechError = when (this) {
        // The Helper's mic was refused or is already exclusively held (its `engine-busy`).
        "microphone-permission-denied", "engine-busy" -> SpeechError.Capture
        // speech-permission-denied / recognizer-unavailable / recognition-failed / anything newer.
        else -> SpeechError.Engine
    }

    /** Map a failed stream onto the seam's [SpeechError] buckets — metadata only, never payload text. */
    private fun SidecarException.toSpeechError(): SpeechError = when (this) {
        // No Helper bound / handshake refused — dictation simply isn't available here.
        is SidecarUnavailableException, is SidecarSecurityException -> SpeechError.Unavailable
        // The Helper failed the stream or the connection dropped mid-utterance.
        else -> SpeechError.Engine
    }

    internal companion object {
        /** The selection rank — above the whisper floor's 0 (ADR-0018), with room for engines between. */
        const val RANK: Int = 10
    }
}
