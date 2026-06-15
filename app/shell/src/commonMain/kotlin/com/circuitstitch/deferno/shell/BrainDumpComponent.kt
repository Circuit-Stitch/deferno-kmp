package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.agent.DraftTask
import com.circuitstitch.deferno.core.agent.DraftTaskProposal
import com.circuitstitch.deferno.core.agent.InferenceResult
import com.circuitstitch.deferno.core.agent.Transcript
import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.speech.ContinuityHint
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechError
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.core.speech.TranscriptEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * The **Brain dump** logic (ADR-0027, #150): the dictation-driven, propose-only Extractor surface.
 * It is deliberately Compose-free so the [BrainDumpScreen] View stays a thin render of this state and
 * the whole flow is unit-testable without a UI.
 *
 * The flow is: continuous on-device speech (the whisper floor) streams a [transcript][BrainDumpState.transcript]
 * (the View owns the RECORD_AUDIO prompt and calls [startDictation] only after grant); [stopDictation]
 * hands the accumulated transcript to the on-device [com.circuitstitch.deferno.core.agent.Extractor]
 * (via [extract]), which returns reviewable [draft Tasks][BrainDumpState.drafts]. Nothing is written
 * until the person [accepts][acceptDraft] a draft — acceptance commits through the **ordinary** online
 * create Command path ([create]), the same seam the New form uses (propose-only, no new write plumbing).
 *
 * Privacy (ADR-0009/0018/0027): the audio and the transcript are never logged or persisted; only a
 * draft the person accepts becomes a real Item.
 */
interface BrainDumpComponent {
    val state: StateFlow<BrainDumpState>

    /**
     * Begin **continuous** [[Dictation]] (#92, ADR-0018). The View calls this only **after** RECORD_AUDIO
     * is granted (it owns the OS prompt). Unlike the New form's per-field [[Utterance]] dictation, this
     * keeps capturing across utterances until [stopDictation]. A no-op when no speech engine is wired.
     */
    fun startDictation()

    /** Stop capturing and run the on-device Extractor over everything dictated so far. */
    fun stopDictation()

    /**
     * The View reports a **denied** RECORD_AUDIO outcome (#92) so the surface shows the gentle "needs
     * microphone access" state — and, when [permanentlyDenied], offer the OS-settings deep-link.
     */
    fun dictationPermissionDenied(permanentlyDenied: Boolean)

    /** Open the OS surface to flip a foreclosed mic permission (#120) — host-routed, a no-op by default. */
    fun openDictationPermissionSettings()

    /** Commit one reviewed draft as a real Task through the ordinary online-only create path (ADR-0027). */
    fun acceptDraft(id: String)

    /** Drop one draft from the review list without creating it. */
    fun dismissDraft(id: String)

    /** Dismiss the surface (the host clears the overlay). */
    fun dismiss()
}

/** The Brain dump surface's render state. */
data class BrainDumpState(
    val phase: Phase = Phase.Idle,
    // Whether the on-device speech engine is available now (model present + supported locale) — the View
    // offers the mic only when true (otherwise a gentle "dictation isn't available" note, never a dead mic).
    val micAvailable: Boolean = false,
    // The live transcript: settled utterances plus the current partial tail while listening, then the final
    // text handed to the Extractor.
    val transcript: String = "",
    // The reviewable draft Tasks the Extractor proposed (empty in [Phase.Review] ⇒ "nothing to add").
    val drafts: List<DraftCard> = emptyList(),
    // A gentle note when the Extractor proposed inter-draft relationships (a subtask tree / sequencing) the
    // flat-create v1 doesn't reconstruct — each draft still becomes a standalone Task (ADR-0027).
    val relationsDropped: Boolean = false,
)

/** Where the Brain dump surface is in its capture → extract → review lifecycle. */
sealed interface Phase {
    /** Idle — nothing captured yet (the start of a session, or after a blank stop). */
    data object Idle : Phase

    /** Capturing speech; [BrainDumpState.transcript] streams live. */
    data object Listening : Phase

    /** Running the on-device Extractor over the transcript. */
    data object Extracting : Phase

    /** Drafts are presented for review (the list may be empty). */
    data object Review : Phase

    /** Extraction couldn't produce drafts — surfaced gently, typed by [reason], never a silent failure. */
    data class Failed(val reason: FailureReason) : Phase

    /** RECORD_AUDIO was denied — the gentle "needs microphone access" (the View offers a retry). */
    data object PermissionDenied : Phase

    /** The mic permission is permanently foreclosed — the View additionally deep-links to OS settings. */
    data object PermissionPermanentlyDenied : Phase
}

/** Why a brain-dump extraction couldn't produce drafts — mapped from the typed [InferenceResult.Failure] / a speech error. */
enum class FailureReason {
    /** No inference engine is set up (Settings → Agent is Off, or the cloud isn't entitled). */
    NotConfigured,

    /** The engine answered but its output didn't validate (even after its repair pass). */
    Malformed,

    /** The engine couldn't be reached (network / auth / quota / 5xx). */
    Transport,

    /** Speech recognition itself failed mid-session. */
    Speech,
}

/** One reviewable draft Task in the list, with where its create is in its lifecycle. */
data class DraftCard(
    val id: String,
    val title: String,
    // A short "Due …" line, or null when the draft carries no deadline.
    val detail: String?,
    val status: DraftStatus = DraftStatus.Pending,
)

/** Where one [DraftCard]'s create is in its lifecycle. */
enum class DraftStatus {
    /** Not yet accepted. */
    Pending,

    /** The create command is in flight. */
    Creating,

    /** Created — the new Task is now observable via the repository `Flow`. */
    Created,

    /** Offline (ADR-0016): nothing was sent; the person reconnects and accepts again. */
    Offline,

    /** A server rejection — a gentle "couldn't save". */
    Failed,
}

/**
 * Default [BrainDumpComponent]. [extract] runs the on-device Extractor (the shell closes the injected
 * `today`/`timeZone` over it); [create] is the shell's online-only create seam — the same one the New
 * form uses (ADR-0016); [onDone] clears the overlay; [scope] runs the streaming capture + the one-shot
 * extract/create. [speech]/[locale] are the AppScope dictation engine + device locale (defaulted so
 * tests build without them — dictation is simply unavailable).
 */
class DefaultBrainDumpComponent(
    private val extract: suspend (Transcript) -> InferenceResult<DraftTaskProposal>,
    private val create: suspend (CreateItem.Payload) -> CommandResult,
    private val onDone: () -> Unit,
    private val scope: CoroutineScope,
    // The Active Account's time zone: a draft's `completeBy` date becomes a `complete_by` instant in it.
    private val timeZone: String = "UTC",
    private val speech: SpeechToText? = null,
    private val locale: String = "en-US",
    // The PermissionPermanentlyDenied affordance (#120), host-routed like the New surface's.
    private val onOpenDictationPermissionSettings: () -> Unit = {},
) : BrainDumpComponent {

    // Trace logger for the brain-dump path (#150). Privacy (ADR-0009/0018/0027): logs only structure —
    // phases, event types, text *lengths*, draft counts — never the transcript text or any draft content.
    private val log = Logger("BrainDump")

    private val _state = MutableStateFlow(BrainDumpState())
    override val state: StateFlow<BrainDumpState> = _state

    /** The active capture collection; cancelled on stop, permission-deny, error, or a new start. */
    private var dictationJob: Job? = null

    /** The settled utterances so far — continuous capture appends each [TranscriptEvent.Final]. */
    private val settled = StringBuilder()

    /** The drafts the last extraction produced, by id, for the accept mapping + relation detection. */
    private var draftsById: Map<String, DraftTask> = emptyMap()

    init {
        // Offer the mic only when the engine is genuinely available now (model present + supported locale).
        val engine = speech
        if (engine == null) {
            log.d { "init: no speech engine wired — dictation unavailable" }
        } else {
            scope.launch {
                val availability = engine.availability(locale)
                log.d { "init: mic availability for $locale = $availability" }
                _state.update { it.copy(micAvailable = availability == SpeechAvailability.Available) }
            }
        }
    }

    override fun startDictation() {
        val engine = speech
        if (engine == null) {
            log.w { "startDictation: no speech engine — ignoring" }
            return
        }
        log.i { "startDictation: continuous capture (locale=$locale)" }
        dictationJob?.cancel()
        settled.clear()
        draftsById = emptyMap()
        _state.update {
            it.copy(phase = Phase.Listening, transcript = "", drafts = emptyList(), relationsDropped = false)
        }
        dictationJob = scope.launch {
            engine.listen(locale, ContinuityHint.Continuous).collect { event ->
                when (event) {
                    // A partial is the running best-guess for the CURRENT utterance — show it as the live
                    // tail after the settled text, REPLACING the prior partial (not appended).
                    is TranscriptEvent.Partial -> {
                        log.v { "event Partial (len=${event.text.length})" }
                        _state.update { it.copy(transcript = withTail(event.text)) }
                    }
                    // A final settles one utterance — fold it into the accumulator and keep listening
                    // (continuous capture: the New form stops here, the brain dump does not).
                    is TranscriptEvent.Final -> {
                        appendSettled(event.text)
                        log.d { "event Final (len=${event.text.length}); settledLen now ${settled.length}" }
                        _state.update { it.copy(transcript = settled.toString()) }
                    }
                    is TranscriptEvent.Error -> onSpeechError(event.reason)
                }
            }
        }
    }

    override fun stopDictation() {
        dictationJob?.cancel()
        dictationJob = null
        // Extract WYSIWYG: whatever the person last saw (settled utterances + any unsettled partial tail).
        val text = _state.value.transcript.trim()
        log.i { "stopDictation: transcriptLen=${text.length}" }
        if (text.isBlank()) {
            log.i { "stopDictation: blank transcript → Idle (the engine produced no text this session)" }
            _state.update { it.copy(phase = Phase.Idle, transcript = "") }
            return
        }
        log.i { "stopDictation: extracting (len=${text.length})" }
        _state.update { it.copy(phase = Phase.Extracting, transcript = text) }
        scope.launch {
            when (val result = extract(Transcript(text))) {
                is InferenceResult.Success -> onProposal(result.value)
                is InferenceResult.Failure -> {
                    log.w { "stopDictation: extract failed → ${result.toReason()}" }
                    _state.update { it.copy(phase = Phase.Failed(result.toReason())) }
                }
            }
        }
    }

    override fun dictationPermissionDenied(permanentlyDenied: Boolean) {
        dictationJob?.cancel()
        dictationJob = null
        _state.update {
            it.copy(phase = if (permanentlyDenied) Phase.PermissionPermanentlyDenied else Phase.PermissionDenied)
        }
    }

    override fun openDictationPermissionSettings() = onOpenDictationPermissionSettings()

    override fun acceptDraft(id: String) {
        val draft = draftsById[id] ?: return
        val card = _state.value.drafts.firstOrNull { it.id == id } ?: return
        // Idempotent: don't re-create one that's already creating or created.
        if (card.status == DraftStatus.Creating || card.status == DraftStatus.Created) {
            log.d { "acceptDraft $id: no-op (status=${card.status})" }
            return
        }
        log.i { "acceptDraft $id: creating" }
        setStatus(id, DraftStatus.Creating)
        scope.launch {
            val next = when (create(draft.toCreatePayload(timeZone, draftsById.keys))) {
                is CommandResult.Accepted -> DraftStatus.Created
                is CommandResult.Offline -> DraftStatus.Offline
                // A pre-flight Rejected never happens for CreateItem, but be total — treat as a soft failure.
                is CommandResult.Rejected, is CommandResult.Failed -> DraftStatus.Failed
            }
            log.i { "acceptDraft $id → $next" }
            setStatus(id, next)
        }
    }

    override fun dismissDraft(id: String) {
        _state.update { it.copy(drafts = it.drafts.filterNot { d -> d.id == id }) }
    }

    override fun dismiss() = onDone()

    /**
     * Stop the continuous capture without extracting — the teardown hook the shell wires to the overlay's
     * `doOnDestroy` so backing out (or an account switch) never leaves the mic hot. A continuous session
     * runs until cancelled (unlike the New form's single-utterance dictation, which self-terminates), so
     * this is required, not optional. Idempotent.
     */
    fun cancelCapture() {
        log.d { "cancelCapture: stopping capture (teardown)" }
        dictationJob?.cancel()
        dictationJob = null
    }

    private fun onProposal(proposal: DraftTaskProposal) {
        draftsById = proposal.drafts.associateBy { it.id }
        val ids = draftsById.keys
        // Flat-create v1 (ADR-0027): inter-draft trees/sequencing aren't reconstructed. Flag it so the
        // View can gently say each becomes a standalone Task.
        val relationsDropped = proposal.drafts.any { d ->
            d.children.isNotEmpty() ||
                d.nextTaskId != null ||
                (d.parentId != null && d.parentId != d.id && d.parentId in ids)
        }
        log.i { "proposal: ${proposal.drafts.size} draft(s), relationsDropped=$relationsDropped → Review" }
        _state.update {
            it.copy(
                phase = Phase.Review,
                drafts = proposal.drafts.map { d -> d.toCard() },
                relationsDropped = relationsDropped,
            )
        }
    }

    private fun onSpeechError(reason: SpeechError) {
        log.w { "speech error: $reason" }
        dictationJob?.cancel()
        dictationJob = null
        _state.update {
            it.copy(
                phase = when (reason) {
                    // A real, settled permission denial (#120): terminal until flipped in OS settings.
                    SpeechError.PermissionDenied -> Phase.PermissionPermanentlyDenied
                    else -> Phase.Failed(FailureReason.Speech)
                },
            )
        }
    }

    private fun setStatus(id: String, status: DraftStatus) {
        _state.update { s -> s.copy(drafts = s.drafts.map { if (it.id == id) it.copy(status = status) else it }) }
    }

    /** Fold one settled utterance into [settled], single-spaced. */
    private fun appendSettled(utterance: String) {
        val u = utterance.trim()
        if (u.isEmpty()) return
        if (settled.isNotEmpty()) settled.append(' ')
        settled.append(u)
    }

    /** The live transcript = settled utterances + the current partial tail. */
    private fun withTail(partial: String): String {
        val base = settled.toString()
        val tail = partial.trim()
        return when {
            tail.isEmpty() -> base
            base.isEmpty() -> tail
            else -> "$base $tail"
        }
    }
}

private fun InferenceResult.Failure.toReason(): FailureReason = when (this) {
    is InferenceResult.Failure.NotConfigured -> FailureReason.NotConfigured
    is InferenceResult.Failure.MalformedOutput -> FailureReason.Malformed
    is InferenceResult.Failure.Transport -> FailureReason.Transport
}

private fun DraftTask.toCard(): DraftCard = DraftCard(
    id = id,
    title = title.trim(),
    detail = completeBy?.let { due ->
        deadlineTimeOfDay?.let { time -> "Due $due at $time" } ?: "Due $due"
    },
)

/**
 * Map an extracted [DraftTask] to the online-only create payload (ADR-0027 flat-create v1). [draftIds]
 * are the ids of the other drafts in the same proposal: a `parentId` pointing at one of them is an
 * inter-draft relation we don't reconstruct yet — the create wire seeds one Item at a time and the
 * Command path doesn't hand back the new id — so it's dropped; only a `parentId` referencing an
 * **existing** Item is kept. The `children` and `nextTaskId` relations have no create-wire home in v1.
 * The date becomes a start-of-day instant in [timeZone] (mirroring `NewState.toPayload`).
 */
internal fun DraftTask.toCreatePayload(timeZone: String, draftIds: Set<String>): CreateItem.Payload {
    val zone = runCatching { TimeZone.of(timeZone) }.getOrDefault(TimeZone.UTC)
    val existingParent = parentId?.takeIf { it.isNotBlank() && it !in draftIds }
    return CreateItem.Payload.Task(
        CreateTaskPayload(
            title = title.trim(),
            description = description?.ifBlank { null },
            completeBy = completeBy?.atStartOfDayIn(zone)?.toString(),
            deadlineTimeOfDay = deadlineTimeOfDay?.toString(),
            parentId = existingParent,
            desire = desire,
            productive = productive,
        ),
    )
}
