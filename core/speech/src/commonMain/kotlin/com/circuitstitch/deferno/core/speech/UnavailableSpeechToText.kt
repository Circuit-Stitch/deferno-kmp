package com.circuitstitch.deferno.core.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The no-op speech engine floor (ADR-0018): always [SpeechAvailability.Unavailable] and its [listen]
 * emits a single [TranscriptEvent.Error] then completes. It is the registered engine on platforms whose
 * real engine hasn't landed yet (desktop/iOS pre-engine, and Android before the whisper engine in #92
 * L1) so the `Set<SpeechToText>` multibinding is never empty and the [SpeechToTextSelector] always has a
 * total fallback. It is the analogue of `InMemorySecretVault` in core:secure — a safe, inert default.
 *
 * It is **measured** (commonTest) — unlike the platform engine actuals, there is real, deterministic
 * behaviour here to pin: never available, never silently succeeds.
 */
object UnavailableSpeechToText : SpeechToText {
    override val id: SpeechEngineId = SpeechEngineId.Unavailable

    /** Below every real engine, so it is only ever chosen when nothing else is available. */
    override val rank: Int = Int.MIN_VALUE

    override val supportsContinuous: Boolean = false

    override suspend fun availability(locale: String): SpeechAvailability =
        SpeechAvailability.Unavailable(UnavailableReason.NoEngine)

    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> =
        flowOf(TranscriptEvent.Error(SpeechError.Unavailable))
}
