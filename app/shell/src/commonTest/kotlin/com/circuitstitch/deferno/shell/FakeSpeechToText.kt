package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.speech.ContinuityHint
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.core.speech.TranscriptEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A controllable [SpeechToText] for the New surface's [[Dictation]] tests (#92): a fixed availability and
 * a caller-supplied [events] flow (e.g. a MutableSharedFlow the test drives event by event).
 */
class FakeSpeechToText(
    private val available: SpeechAvailability = SpeechAvailability.Available,
    private val events: Flow<TranscriptEvent> = emptyFlow(),
) : SpeechToText {
    override val id: SpeechEngineId = SpeechEngineId("fake")
    override val rank: Int = 0
    override val supportsContinuous: Boolean = true
    override suspend fun availability(locale: String): SpeechAvailability = available
    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> = events
}
