package com.circuitstitch.deferno.core.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * A configurable [SpeechToText] fake for the selector + seam tests (ADR-0006 — the seam and selector are
 * measured with a fake engine). Records how it was [listen]ed to, and replays a scripted [events] flow.
 */
class FakeSpeechToText(
    override val id: SpeechEngineId,
    override val rank: Int = 0,
    override val supportsContinuous: Boolean = true,
    private val availability: SpeechAvailability = SpeechAvailability.Available,
    private val events: List<TranscriptEvent> = listOf(TranscriptEvent.Final("ok")),
    /** Per-locale availability override (e.g. English-only), takes precedence over [availability]. */
    private val availabilityForLocale: ((String) -> SpeechAvailability)? = null,
) : SpeechToText {
    var listens: Int = 0
        private set
    var lastListenLocale: String? = null
        private set
    var lastListenHint: ContinuityHint? = null
        private set

    override suspend fun availability(locale: String): SpeechAvailability =
        availabilityForLocale?.invoke(locale) ?: availability

    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> {
        listens++
        lastListenLocale = locale
        lastListenHint = continuityHint
        return events.asFlow()
    }
}
