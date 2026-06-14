package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.shacl.ShaclFloor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import com.circuitstitch.deferno.shacl.DraftTask as ShaclDraftTask

/**
 * The on-device deterministic-floor [InferenceEngine] (ADR-0027): the vendored shacl-aio crate
 * ([ShaclFloor], libshacl_aio.so over JNA) wrapped at the inference seam, so it is one selectable
 * engine alongside the cloud relay. Zero ML, zero network — [InferenceEngineId.OnDeviceFloor], ungated.
 *
 * The seam is LLM-shaped (instructions + free-text content), but the floor wants the bare transcript and
 * a clock, so it recovers them from the request content ([parseFloorExtractorInput]) and reconstructs
 * `now` at noon UTC of the extractor's `today` (date-only resolution — the floor emits date deadlines).
 * The floor's draft Tasks are mapped to the shared [DraftTasks] shape and decoded against the request's
 * schema ([InferenceSchema.parse]) — so a non-extractor schema fails typed ([MalformedOutput]), never
 * crashes. Only the deterministic floor `extract` is exposed; a planned floor+LLM hybrid is its own engine.
 *
 * Native-backed androidMain actual — exercised on-device, excluded from the headless coverage gate
 * (CoverageConfig), exactly like `WhisperSpeechToText`. The routing and content recovery it leans on are
 * commonMain and measured.
 */
@OptIn(ExperimentalTime::class)
class ShaclFloorInferenceEngine(
    private val json: Json = Json,
) : InferenceEngine {
    override suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T> {
        val input = parseFloorExtractorInput(request.content)
            ?: return InferenceResult.Failure.MalformedOutput("floor: unrecognised extractor content")
        val now = Instant.parse("${input.today}T12:00:00Z")
        val drafts = ShaclFloor.extract(input.transcript, now).map { it.toAgentDraft() }
        return request.schema.parse(json.encodeToString(DraftTasks.serializer(), DraftTasks(drafts)))
    }
}

/**
 * Map a floor [draft][ShaclDraftTask] to the shared [DraftTask]. The floor emits document-order ids
 * (`task1`, `task2`, …) and a deadline as an RFC3339 instant (end-of-day) → take the calendar-date part.
 * `tags` and a deadline time-of-day are dropped — the shared draft model carries neither yet.
 */
private fun ShaclDraftTask.toAgentDraft(): DraftTask = DraftTask(
    id = id,
    title = title,
    description = description,
    completeBy = completeBy?.let { runCatching { LocalDate.parse(it.substringBefore('T')) }.getOrNull() },
    desire = desire,
    productive = productive,
    parentId = parentId,
    nextTaskId = nextTaskId,
)
