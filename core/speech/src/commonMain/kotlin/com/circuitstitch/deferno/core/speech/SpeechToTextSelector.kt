package com.circuitstitch.deferno.core.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Capability-tiered engine selection with **structural never-cloud** (ADR-0018). The app-facing
 * [SpeechToText]: per [listen]/[availability] it picks, from the registered [engines], the engine to
 * recognize with — honouring the device-local [SpeechEnginePreference], then falling to the highest-ranked
 * engine that is [SpeechAvailability.Available] now (the whisper baseline is the floor).
 *
 * **Never cloud is structural, not a runtime check:** the selector can only choose from the engines
 * actually registered in the DI graph, and no cloud engine is *ever* registered — native engines are
 * forced on-device or report unavailable. So no input or preference can make the selector pick a server
 * recognizer; a chosen-but-unavailable engine simply falls to the on-device floor.
 *
 * Implemented as a [SpeechToText] so the app depends on the single seam; it is bound at AppScope over the
 * `Set<SpeechToText>` multibinding. It is **measured** (commonTest) — the selection rules are real logic.
 */
class SpeechToTextSelector(
    private val engines: Set<SpeechToText>,
    private val preference: SpeechEnginePreference,
) : SpeechToText {
    override val id: SpeechEngineId = SpeechEngineId.Selector

    /** Not itself ranked — it is the composite over the ranked [engines]. */
    override val rank: Int = 0

    /** Continuous capture is possible iff some registered engine supports it. */
    override val supportsContinuous: Boolean = engines.any { it.supportsContinuous }

    /**
     * Whether dictation is possible at all for [locale] (drives the mic affordance's enabled state). It
     * is [SpeechAvailability.Available] when some engine could be selected for a normal utterance;
     * otherwise it surfaces the highest-ranked engine's [SpeechAvailability.Unavailable] reason (so the
     * UI can say *why* — model still arriving, English-only, …), or [UnavailableReason.NoEngine] when
     * none is registered. One exception: an **absent optional fast path**
     * ([UnavailableReason.NotInstalled] — e.g. the Sidecar engine on a machine with no Helper, its
     * permanent normal state) never masks a real engine's actionable reason; it surfaces only when
     * every registered engine is absent.
     */
    override suspend fun availability(locale: String): SpeechAvailability {
        if (select(locale, ContinuityHint.Utterance) != null) return SpeechAvailability.Available
        val reasons = engines
            .sortedByDescending { it.rank }
            .mapNotNull { (it.availability(locale) as? SpeechAvailability.Unavailable)?.reason }
        val fallbackReason = reasons.firstOrNull { it != UnavailableReason.NotInstalled }
            ?: reasons.firstOrNull()
            ?: UnavailableReason.NoEngine
        return SpeechAvailability.Unavailable(fallbackReason)
    }

    /**
     * Recognize via the engine [select]ed for ([locale], [continuityHint]); if none is available, emit a
     * single [TranscriptEvent.Error] ([SpeechError.Unavailable]) rather than failing silently (ADR-0018).
     * The engine is reselected lazily when the flow is collected, so a model that arrives between calls is
     * picked up on the next [listen].
     */
    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> = flow {
        val engine = select(locale, continuityHint)
        if (engine == null) {
            emit(TranscriptEvent.Error(SpeechError.Unavailable))
        } else {
            emitAll(engine.listen(locale, continuityHint))
        }
    }

    /**
     * The chosen engine for ([locale], [continuityHint]), or `null` when none is available:
     *  1. consider only engines whose [availability] is [SpeechAvailability.Available] for [locale],
     *  2. for a [ContinuityHint.Continuous] session, drop engines that don't [supportsContinuous],
     *  3. honour an explicit [SpeechEnginePreference] when that engine is among the available set,
     *  4. otherwise take the highest [rank] available engine (the whisper floor wins by being the only
     *     always-available real engine).
     */
    suspend fun select(locale: String, continuityHint: ContinuityHint): SpeechToText? {
        val available = engines
            .filter { it.availability(locale) == SpeechAvailability.Available }
            .filter { continuityHint != ContinuityHint.Continuous || it.supportsContinuous }
        if (available.isEmpty()) return null
        available.firstOrNull { it.id == preference.preferredEngine() }?.let { return it }
        return available.maxByOrNull { it.rank }
    }
}
