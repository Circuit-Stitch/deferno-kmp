package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.sidecar.SidecarException
import com.circuitstitch.deferno.core.sidecar.SidecarSpeechPort
import com.circuitstitch.deferno.core.sidecar.SidecarSpeechReadiness
import com.circuitstitch.deferno.core.sidecar.SpeechPreflight
import com.circuitstitch.deferno.core.sidecar.TranscriptWire
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scriptable [SidecarSpeechPort] for the [SidecarSpeechToText] unit tests: the engine is pure mapping
 * over this seam, so its tests script readiness + the typed event stream and nothing else. The wire and
 * socket below the port are exercised by core/sidecar's own port + E2E suites and by
 * [SidecarSpeechToTextSelectorE2ETest].
 */
internal class FakeSidecarSpeechPort(
    var readiness: SidecarSpeechReadiness = SidecarSpeechReadiness.Ready,
    /** What the seam-owned permission preflight settles to (#172). */
    var preflight: SpeechPreflight = SpeechPreflight.Clear,
    /** The events a subscription replays. */
    var events: List<TranscriptWire> = emptyList(),
    /** Thrown after [events] (no Helper bound, handshake refused, or dropped mid-utterance). */
    var failure: SidecarException? = null,
) : SidecarSpeechPort {

    var readinessChecks: Int = 0
        private set
    var preflights: Int = 0
        private set
    var subscriptions: Int = 0
        private set

    override suspend fun readiness(): SidecarSpeechReadiness {
        readinessChecks++
        return readiness
    }

    override suspend fun preflight(): SpeechPreflight {
        preflights++
        return preflight
    }

    override fun transcripts(): Flow<TranscriptWire> = flow {
        subscriptions++
        events.forEach { emit(it) }
        failure?.let { throw it }
    }
}
