package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.sidecar.PermissionStatusValue
import com.circuitstitch.deferno.core.sidecar.PermissionStatusWire
import com.circuitstitch.deferno.core.sidecar.QueryPermissionWire
import com.circuitstitch.deferno.core.sidecar.SidecarCapabilities
import com.circuitstitch.deferno.core.sidecar.SidecarClient
import com.circuitstitch.deferno.core.sidecar.SidecarException
import com.circuitstitch.deferno.core.sidecar.SidecarJson
import com.circuitstitch.deferno.core.sidecar.SidecarMethods
import com.circuitstitch.deferno.core.sidecar.SidecarPermissionCapabilities
import com.circuitstitch.deferno.core.sidecar.SidecarSecurityException
import com.circuitstitch.deferno.core.sidecar.SidecarUnavailableException
import com.circuitstitch.deferno.core.sidecar.TranscriptWire
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException

/**
 * The Sidecar-hosted native speech engine (#119, ADR-0024): dictation runs in the per-OS native Helper
 * (SFSpeechRecognizer on macOS, #121) and reaches the JVM as a [SidecarMethods.SubscribeTranscript]
 * server stream over the peer-authenticated socket. The seam stays at the **Transcript altitude**
 * (ADR-0018): the Helper owns its own mic + recognizer and emits **text — no PCM ever crosses the
 * socket**; this engine just condenses the contract-owned [TranscriptWire] to the domain
 * [TranscriptEvent] at the edge (ADR-0011).
 *
 * **Availability is the Helper's ready signal**, not optimism: [SpeechAvailability.Available] only when
 * the Helper is connected **and** advertised [SidecarCapabilities.SpeechTranscribe] in its `welcome`
 * (it only does so when it can genuinely construct the on-device recognizer) **and** the Speech TCC
 * permission isn't denied/restricted. Anything less — no Helper bound (the normal state on
 * Linux/Windows), handshake refused, capability absent — reports [UnavailableReason.NotReady], so the
 * [SpeechToTextSelector] keeps the always-available whisper floor (ADR-0018/0024). A `not_determined`
 * permission stays Available: introspection never prompts — the first real subscribe is what fires the
 * OS prompt (the Helper's `ensureAuthorized`).
 *
 * **English-only v1 (ADR-0018):** the Helper recognizes `en-US`; a non-English locale reports
 * [UnavailableReason.UnsupportedLocale] locally, without dialing.
 *
 * **Privacy (ADR-0009/0018):** events carry [[Transcript]] text — never logged here; failures map to
 * non-PII [SpeechError]s.
 *
 * Measured (jvmTest, ADR-0006): unlike the whisper actuals (mic + model on a real desktop), this engine
 * is pure socket logic, exercised end-to-end on the Linux fast path against the stub Helper.
 */
internal class SidecarSpeechToText(
    private val client: SidecarClient,
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
        !locale.isEnglish() -> SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale)
        else -> try {
            client.connect()
            when {
                SidecarCapabilities.SpeechTranscribe !in client.capabilities() ->
                    SpeechAvailability.Unavailable(UnavailableReason.NotReady)

                speechPermissionBlocked() ->
                    SpeechAvailability.Unavailable(UnavailableReason.NotReady)

                else -> SpeechAvailability.Available
            }
        } catch (_: SidecarException) {
            // No Helper bound / handshake refused — the normal absent-fast-path state, not an error.
            SpeechAvailability.Unavailable(UnavailableReason.NotReady)
        }
    }

    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> = flow {
        if (!locale.isEnglish()) {
            emit(TranscriptEvent.Error(SpeechError.Unavailable))
            return@flow
        }
        try {
            client.openStream(SidecarMethods.SubscribeTranscript).collect { event ->
                emit(SidecarJson.decodeFromJsonElement(TranscriptWire.serializer(), event).toEvent())
            }
        } catch (_: SidecarUnavailableException) {
            emit(TranscriptEvent.Error(SpeechError.Unavailable))
        } catch (_: SidecarSecurityException) {
            emit(TranscriptEvent.Error(SpeechError.Unavailable))
        } catch (_: SidecarException) {
            // The Helper failed the stream or the connection dropped mid-utterance.
            emit(TranscriptEvent.Error(SpeechError.Engine))
        } catch (_: SerializationException) {
            // An event this client can't read — a Helper bug, not something to crash dictation over.
            emit(TranscriptEvent.Error(SpeechError.Engine))
        }
    }

    /**
     * Whether the Speech TCC state forecloses recognition. Only `denied`/`restricted` block —
     * `not_determined` must stay selectable so the first real subscribe can fire the OS prompt. Skipped
     * when the Helper doesn't broker permissions; #120 widens this into the full permission UX.
     */
    private suspend fun speechPermissionBlocked(): Boolean {
        if (SidecarCapabilities.Permissions !in client.capabilities()) return false
        val result = client.request(
            SidecarMethods.QueryPermission,
            SidecarJson.encodeToJsonElement(
                QueryPermissionWire.serializer(),
                QueryPermissionWire(SidecarPermissionCapabilities.Speech),
            ),
        ) ?: return false
        val status = SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), result).status
        return status == PermissionStatusValue.DENIED || status == PermissionStatusValue.RESTRICTED
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

    private fun String.isEnglish(): Boolean = lowercase().startsWith("en")

    internal companion object {
        /** The selection rank — above the whisper floor's 0 (ADR-0018), with room for engines between. */
        const val RANK: Int = 10
    }
}
