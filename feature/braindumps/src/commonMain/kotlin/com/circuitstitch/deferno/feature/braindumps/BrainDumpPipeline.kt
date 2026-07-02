package com.circuitstitch.deferno.feature.braindumps

import com.circuitstitch.deferno.core.agent.DraftTask
import com.circuitstitch.deferno.core.agent.Extractor
import com.circuitstitch.deferno.core.agent.InferenceResult
import com.circuitstitch.deferno.core.agent.Transcript
import com.circuitstitch.deferno.core.data.braindump.BrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.BrainDumpSalvageCounter
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.brainDumpRecordingPlaceholderId
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftId
import com.circuitstitch.deferno.core.model.BrainDumpDraftStatus
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The shared, platform-neutral Brain dump processing pipeline (#265, ADR-0037): one recorded take in, either
 * draft Tasks **or** a single **[[Salvage draft]]** out. Android's `BrainDumpWorker` delegates here (the iOS
 * runner will too, #267) so the orchestration — and the cross-platform guarantee that *no take is ever wasted*
 * — lives in one place. The shape is `transcribe → extract → (persist drafts | Salvage draft)`, with transcription
 * ([BrainDumpTake.transcribe]), extraction ([Extractor]), audio retention ([BrainDumpRecordingStore]) and
 * completion notification ([BrainDumpNotifier]) as **injected platform seams**.
 *
 * **Salvage** (CONTEXT.md): when a take can't become real drafts — transcription produced nothing, no on-device
 * engine is configured, the engine failed, or it found no tasks — the pipeline writes exactly one `Brain dump #n`
 * draft to the Inbox instead of discarding the take. Its notes carry the spoken [[Transcript]] when there is
 * one (the most salvageable artifact), or a short reason when there isn't. A salvage **always** keeps the
 * recording, even when the keep-recordings preference is off — the audio is then the only artifact left.
 *
 * The pipeline is **clock-free**: [createdAt] is captured at the user's Stop action and threaded in, so a take's
 * drafts and its retained recording share one instant (the [brainDumpRecordingPlaceholderId] the Inbox accept
 * keys on). It never touches `Clock.System` (ADR feedback `no-real-clock-dates`).
 */
class BrainDumpPipeline(
    private val extractor: Extractor,
    private val drafts: BrainDumpDraftSink,
    private val recordings: BrainDumpRecordingStore,
    private val keepRecordings: KeepBrainDumpRecordingsPreference,
    private val salvageCounter: BrainDumpSalvageCounter,
    private val notifications: BrainDumpNotificationPreference,
    private val notifier: BrainDumpNotifier,
    // Persisted draft prose (title/notes freeze in the locale they were written in). Injected so each
    // platform passes its localized words; the defaults keep the English wiring working unchanged.
    private val salvageReason: String = SALVAGE_REASON,
    private val salvageTitle: (Int) -> String = { "Brain dump #$it" },
) {
    /**
     * Process one recorded [take]. [today]/[timeZone] are the captured date context extraction resolves relative
     * dates against; [createdAt] is the recording's single instant (see class doc). Always persists exactly one
     * outcome: the extracted drafts, or one salvage draft — never nothing.
     */
    suspend fun process(take: BrainDumpTake, today: LocalDate, timeZone: String, createdAt: Instant) {
        val transcript = take.transcribe().trim()
        val produced = if (transcript.isBlank()) {
            emptyList() // never heard / couldn't transcribe — salvage with a reason, don't consult the engine
        } else {
            when (val result = extractor.extract(Transcript(transcript), today, timeZone)) {
                is InferenceResult.Success -> result.value.drafts.mapIndexed { index, draft -> draft.toDraft(createdAt, index) }
                // No engine, transport, or malformed output — salvage with the transcript (the useful artifact).
                is InferenceResult.Failure -> emptyList()
            }
        }

        val outcome = if (produced.isNotEmpty()) {
            produced.forEach { drafts.upsert(it) }
            if (keepRecordings.enabled()) retain(take, createdAt)
            BrainDumpOutcome.Drafts(produced.size)
        } else {
            // The transcript is the most salvageable note; fall back to a reason only when there is none.
            val notes = transcript.ifBlank { salvageReason }
            drafts.upsert(salvageDraft(salvageTitle(salvageCounter.next()), notes, createdAt))
            retain(take, createdAt) // salvage keeps the recording even when keep-recordings is off
            BrainDumpOutcome.Salvaged
        }

        // Opt-in completion notification (device-local App setting, default off — #266/#271): off → the drafts
        // simply appear in the Inbox with no notification.
        if (notifications.enabled()) notifier.completed(outcome)
    }

    // Best-effort, like Android's worker: a failed save must never lose the drafts/salvage already persisted.
    private suspend fun retain(take: BrainDumpTake, createdAt: Instant) {
        runCatching {
            recordings.retain(
                id = brainDumpRecordingPlaceholderId(createdAt),
                bytes = take.readBytes(),
                createdAt = createdAt,
            )
        }
    }
}

/**
 * One recorded Brain dump take handed to [BrainDumpPipeline]. Platform-owned: Android wraps the temp WAV `File`,
 * iOS wraps the durable `pending/` WAV (ADR-0037). The seam sits at the Transcript altitude (ADR-0018) — the
 * pipeline never sees PCM, only the transcribed text and the bytes to retain.
 */
interface BrainDumpTake {
    /** On-device transcription of the recording; **blank** when transcription is unavailable or failed. */
    suspend fun transcribe(): String

    /** The raw WAV bytes, retained on-device as the recording attachment (read lazily, only when retaining). */
    suspend fun readBytes(): ByteArray
}

/** Persists a Brain dump draft (normal or salvage). Backed by `BrainDumpDraftRepository.upsert` on each platform. */
fun interface BrainDumpDraftSink {
    suspend fun upsert(draft: BrainDumpDraft)
}

/**
 * Retains a take's WAV bytes on-device under [id] (the shared [brainDumpRecordingPlaceholderId] key) so the
 * Inbox accept can attach it to the created Task. Backed by `LocalAttachmentRepository.save` (each platform
 * names the file from [createdAt]).
 */
fun interface BrainDumpRecordingStore {
    suspend fun retain(id: String, bytes: ByteArray, createdAt: Instant)
}

/** What a finished take produced — drives the (opt-in, #266/#271) completion notification. */
sealed interface BrainDumpOutcome {
    /** [count] real draft Tasks landed in the Inbox ([count] is always ≥ 1). */
    data class Drafts(val count: Int) : BrainDumpOutcome

    /** The take couldn't become drafts; one Salvage draft (with the recording) landed in the Inbox. */
    data object Salvaged : BrainDumpOutcome
}

/**
 * Posts the completion notification for a finished take. The opt-in gate (the "Brain dump notifications" App
 * setting, default off — #266/#271) lives in the platform impl / its caller; the pipeline always reports the
 * outcome, off → the impl posts nothing.
 */
fun interface BrainDumpNotifier {
    fun completed(outcome: BrainDumpOutcome)
}

/** The salvage note when there is no transcript to keep (couldn't transcribe / nothing heard). Defined once,
 * identical on every platform (ADR-0037). */
internal const val SALVAGE_REASON =
    "This recording couldn't be turned into tasks, so it's saved here for you to review."

/** Canonical PCM16 WAV header size (`WavCodec`, core:speech). A recording no larger than this captured no audio. */
internal const val WAV_HEADER_BYTES: Long = 44L

/**
 * Whether a take is **trivially empty** (an accidental tap, no real audio) — dropped at the recorder seam before
 * the pipeline, *not* salvaged (ADR-0037). Defined once so every platform's recorder uses the same threshold.
 */
fun isTrivialRecording(byteCount: Long): Boolean = byteCount <= WAV_HEADER_BYTES

// A Salvage draft: a Ready BrainDumpDraft titled `Brain dump #n`, id keyed off the take's instant so a
// re-processed take upserts the same row (idempotent) rather than duplicating (#270).
private fun salvageDraft(title: String, notes: String, createdAt: Instant): BrainDumpDraft = BrainDumpDraft(
    id = BrainDumpDraftId("salvage-${createdAt.toEpochMilliseconds()}"),
    title = title,
    notes = notes,
    status = BrainDumpDraftStatus.Ready,
    createdAt = createdAt,
)

// The Extractor's draft → the persisted draft. Flat: BrainDumpDraft drops desire/productive/parent/sequence
// (the Inbox commits a flat Task; relations are a follow-up).
//
// The persisted id is keyed off the take's instant + the draft's [index], NOT the model-authored DraftTask.id
// (a non-deterministic LLM emits fresh ids each run). So reprocessing the same take — the #270 relaunch sweep
// or BGProcessingTask backstop re-running after an app death between the upsert and the WAV delete — upserts
// the SAME rows (insertOrReplace replaces, never duplicates), matching the salvage path's idempotency. The
// model id isn't load-bearing downstream (relations are dropped here). ponytail: a re-run that extracts a
// DIFFERENT count could leave a trailing stale draft (rare model non-determinism); a take-scoped delete is the
// follow-up if that ever bites.
private fun DraftTask.toDraft(createdAt: Instant, index: Int): BrainDumpDraft = BrainDumpDraft(
    id = BrainDumpDraftId("draft-${createdAt.toEpochMilliseconds()}-$index"),
    title = title,
    notes = description,
    completeBy = completeBy,
    deadlineTimeOfDay = deadlineTimeOfDay,
    status = BrainDumpDraftStatus.Ready,
    createdAt = createdAt,
)
