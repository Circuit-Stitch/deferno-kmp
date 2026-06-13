package com.circuitstitch.deferno.macos.agent

import com.circuitstitch.deferno.core.agent.DraftTask
import com.circuitstitch.deferno.core.agent.Extractor
import com.circuitstitch.deferno.core.agent.InferenceResult
import com.circuitstitch.deferno.core.agent.Transcript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** One proposed draft Task, flattened to Swift-friendly Strings (Kotlin/Native erases `LocalDate` etc.). */
data class DraftPreview(
    val title: String,
    val subtitle: String?,
)

/**
 * The Swift-facing bridge for the Phase-3 Extractor demo (ADR-0029): it runs the **propose-only**
 * Brain-dump [Extractor] over a transcript through the on-device [NativeInference] engine and hands
 * Swift a flat preview list. Kotlin owns the coroutine and the propose-only validation; Swift only
 * supplies the transcript text and renders the drafts (nothing is committed — acceptance still goes
 * through the ordinary Command path, ADR-0027). Mirrors the SKIE-free callback idiom of `bridge/`.
 *
 * Constructed only when an engine is injected (see `DefernoDemoRoot.draftTasks`); [isAvailable]
 * additionally reflects whether Apple Intelligence is ready *right now*, so the panel can disable the
 * trigger and explain why.
 */
class DraftTasksBridge internal constructor(
    private val native: NativeInference,
    private val today: LocalDate,
    private val timeZone: String,
) {
    private val extractor = Extractor(NativeInferenceEngine(native))
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Apple Intelligence is downloaded and ready on this Mac right now. */
    val isAvailable: Boolean get() = native.isAvailable()

    /**
     * Extract draft Tasks from [transcript]. [onResult] receives the proposed drafts (empty for empty
     * or garbled input); [onFailure] receives a short, content-free [InferenceResult.Failure.detail]
     * when no engine is configured or the model output couldn't be validated.
     */
    fun extract(
        transcript: String,
        onResult: (List<DraftPreview>) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        scope.launch {
            when (val result = extractor.extract(Transcript(transcript), today, timeZone)) {
                is InferenceResult.Success -> onResult(result.value.drafts.map { it.preview() })
                is InferenceResult.Failure -> onFailure(result.detail)
            }
        }
    }
}

private fun DraftTask.preview(): DraftPreview = DraftPreview(
    title = title,
    subtitle = listOfNotNull(
        description?.takeIf { it.isNotBlank() },
        completeBy?.let { "Due $it" },
    ).joinToString(" · ").ifBlank { null },
)
