package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant

/**
 * The **New** create-surface logic (#71, ADR-0015/0016): the explicit kind picker + per-kind form
 * state + the online-only create dispatch. It is deliberately Compose-free so the [NewScreen] View
 * stays a thin render of this state, and so the create flow is unit-testable without a UI.
 *
 * The picker is an **explicit** Task/Habit/Chore/Event segmented choice (ADR-0015 — never inferred
 * from field content); the form adapts to [selectedKind]. Submitting routes through the shell's
 * online-only [create] seam (the command executor's `CreateItem`), and the resulting [status] tells
 * the View whether to dismiss (created), show "reconnect to save" ([NewStatus.Offline]), or show a
 * gentle error ([NewStatus.Failed]) — nothing is enqueued offline (ADR-0016).
 */
interface NewComponent {
    val state: StateFlow<NewState>

    /** Pick the kind to create — the explicit segmented control (ADR-0015). */
    fun selectKind(kind: ItemKind)

    fun setTitle(title: String)
    fun setNotes(notes: String)

    /**
     * Set the Event's **fixed start** (`complete_by`) — the one field an Event create genuinely
     * requires (AC #2, FIX 1). `null` clears it. Ignored by the non-Event kinds.
     */
    fun setStart(start: Instant?)

    /** Set the Event's optional **end** (`end_time`); `null` clears it (omitted from the POST). */
    fun setEnd(end: Instant?)

    /**
     * Set the item's **date** (#74) — the day a Task/Habit/Chore anchors to, mapped to `complete_by`.
     * It is also the Event's fallback start when no explicit [setStart] is given. `null` clears it. This
     * is the field the Calendar FAB pre-dates to the selected day.
     */
    fun setDate(date: LocalDate?)

    /** Submit the per-kind form via the online-only create path. */
    fun submit()

    /** Dismiss the surface (the host clears the overlay). */
    fun dismiss()

    /**
     * Begin **[[Dictation]]** into [field] (#92, ADR-0018): on-device speech streams as partial
     * [[Transcript]] text into the focused title/notes field and settles to a final result. The View
     * calls this only **after** RECORD_AUDIO is granted (it owns the OS prompt); dictation only fills
     * text — it never infers the kind or any other field (ADR-0015), and create still gates on
     * connectivity (ADR-0016). A no-op when no speech engine is wired.
     */
    fun startDictation(field: DictationField)

    /** Stop an in-progress dictation, keeping whatever text has already streamed into the field. */
    fun stopDictation()

    /**
     * The View reports a **denied** RECORD_AUDIO outcome (#92) so the surface can show the gentle
     * "needs microphone access" state — and, when [permanentlyDenied], offer the OS-settings deep-link.
     */
    fun dictationPermissionDenied(permanentlyDenied: Boolean)
}

/** Which text field a [[Dictation]] fills (#92). The mic affordance sits on each. */
enum class DictationField { Title, Notes }

/** Where the New surface is in its [[Dictation]] lifecycle (#92, ADR-0018), independent of create [NewStatus]. */
sealed interface DictationStatus {
    /** Not dictating. */
    data object Idle : DictationStatus

    /** Capturing speech; partials are streaming into the focused field. */
    data object Listening : DictationStatus

    /** RECORD_AUDIO was denied — the gentle "needs microphone access" (the View offers a retry). */
    data object PermissionDenied : DictationStatus

    /** RECORD_AUDIO was permanently denied — the View additionally deep-links to OS settings. */
    data object PermissionPermanentlyDenied : DictationStatus

    /** Recognition failed (engine/capture/unavailable) — surfaced gently, never a silent failure. */
    data class Error(val reason: SpeechError) : DictationStatus
}

/** The New surface's render state. */
data class NewState(
    val selectedKind: ItemKind = ItemKind.Task,
    val title: String = "",
    val notes: String = "",
    // An Event has a fixed start/end window (CONTEXT.md → Event; AC #2). The start is required for an
    // Event create — the v0.1 `POST /events` wire requires a non-empty `complete_by` (ADR-0011); the
    // end is optional. The other kinds ignore these. (No `location` field: it is absent from the v0.1
    // contract — contracts/openapi-0.1.json carries no location anywhere — so the client cannot send
    // one; a location is a documented backend follow-up, not invented on the wire.)
    val start: Instant? = null,
    val end: Instant? = null,
    // The item's date (#74): the Task/Habit/Chore `complete_by` anchor, and the Event's fallback start.
    // The Calendar FAB pre-dates this to the selected day; the New form surfaces it for the non-Event kinds.
    val date: LocalDate? = null,
    val status: NewStatus = NewStatus.Editing,
    // Dictation (#92, ADR-0018), orthogonal to the create [status]. [dictationAvailable] gates whether the
    // mic affordance is offered at all (the engine is available: model present + supported locale);
    // [dictation] is the active lifecycle/permission state; [dictationField] is the field currently being
    // filled (null when idle), so the View can show that field's mic as active.
    val dictationAvailable: Boolean = false,
    val dictation: DictationStatus = DictationStatus.Idle,
    val dictationField: DictationField? = null,
) {
    /**
     * Create is enabled only with a non-blank title (the one universally-required field) — and, for an
     * **Event**, a fixed start: either an explicit [start] or a pre-dated [date] fallback (a bare Event
     * create with no start POSTs `complete_by:""`, which the server rejects — FIX 1, AC #2). The
     * recurring kinds default their cadence (recurrence picker is a documented v1 follow-up).
     */
    val canSubmit: Boolean
        get() = title.isNotBlank() &&
            status != NewStatus.Submitting &&
            (selectedKind != ItemKind.Event || start != null || date != null)
}

/** Where the New surface is in its create lifecycle. */
sealed interface NewStatus {
    data object Editing : NewStatus
    data object Submitting : NewStatus

    /** Offline (ADR-0016): the gentle "reconnect to save"; nothing was enqueued. */
    data object Offline : NewStatus

    /** A server rejection — a gentle error [message]. */
    data class Failed(val message: String) : NewStatus
}

/**
 * Default [NewComponent]. [create] is the shell's online-only create seam; [onCreated] is invoked when
 * the server confirms the create (the host dismisses the overlay and the new row is already observable
 * via the repository `Flow`). [launch] runs the suspending create on the shell's scope.
 */
class DefaultNewComponent(
    private val create: suspend (CreateItem.Payload) -> CommandResult,
    private val onCreated: () -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
    // The Active Account's time zone (#74): a pre-dated [date] becomes a `complete_by` instant in it.
    private val tz: String = "UTC",
    // The pre-dated day the Calendar FAB opens New on (#74); `null` opens an undated form.
    initialDate: LocalDate? = null,
    // Dictation (#92, ADR-0018): the on-device [SpeechToText] (the selector) the mic drives, the device
    // [locale] it recognizes (a non-English locale reports unavailable, never mis-transcribes), and the
    // [dictationScope] the streaming listen() runs/cancels on. All defaulted so the shell/desktop tests
    // build without them — dictation is simply unavailable (no mic) when no engine/scope is supplied.
    private val speech: SpeechToText? = null,
    private val locale: String = "en-US",
    private val dictationScope: CoroutineScope? = null,
) : NewComponent {

    private val _state = MutableStateFlow(NewState(date = initialDate))
    override val state: StateFlow<NewState> = _state

    /** The active dictation collection; cancelled on stop, permission-deny, or a new start. */
    private var dictationJob: Job? = null

    /** The field's text at the moment dictation started — partials replace only the dictated suffix. */
    private var dictationBaseText: String = ""

    init {
        // Offer the mic only when the engine is genuinely available now (model present + supported
        // locale, ADR-0018/0019). Queried off the UI path on the dictation scope.
        val engine = speech
        val scope = dictationScope
        if (engine != null && scope != null) {
            scope.launch {
                val available = engine.availability(locale) == SpeechAvailability.Available
                _state.update { it.copy(dictationAvailable = available) }
            }
        }
    }

    override fun selectKind(kind: ItemKind) = _state.update { it.copy(selectedKind = kind, status = NewStatus.Editing) }
    override fun setTitle(title: String) = _state.update { it.copy(title = title, status = NewStatus.Editing) }
    override fun setNotes(notes: String) = _state.update { it.copy(notes = notes, status = NewStatus.Editing) }
    override fun setStart(start: Instant?) = _state.update { it.copy(start = start, status = NewStatus.Editing) }
    override fun setEnd(end: Instant?) = _state.update { it.copy(end = end, status = NewStatus.Editing) }
    override fun setDate(date: LocalDate?) = _state.update { it.copy(date = date, status = NewStatus.Editing) }

    override fun submit() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        _state.update { it.copy(status = NewStatus.Submitting) }
        launch {
            when (create(snapshot.toPayload(tz))) {
                is CommandResult.Accepted -> onCreated()
                is CommandResult.Offline -> _state.update { it.copy(status = NewStatus.Offline) }
                is CommandResult.Failed -> _state.update { it.copy(status = NewStatus.Failed("Could not save. Try again.")) }
                // The create gate never rejects pre-flight (CreateItem has no enabledFor rule), but be total.
                is CommandResult.Rejected -> _state.update { it.copy(status = NewStatus.Failed("Could not save.")) }
            }
        }
    }

    override fun dismiss() = onCreated()

    override fun startDictation(field: DictationField) {
        val engine = speech ?: return
        val scope = dictationScope ?: return
        dictationJob?.cancel()
        // Capture the existing text so streaming partials replace only the dictated suffix (the person
        // keeps anything they had already typed). Dictation fills text only — never the kind (ADR-0015).
        dictationBaseText = _state.value.textOf(field)
        _state.update { it.copy(dictationField = field, dictation = DictationStatus.Listening) }
        dictationJob = scope.launch {
            engine.listen(locale, ContinuityHint.Utterance).collect { event ->
                when (event) {
                    is TranscriptEvent.Partial ->
                        _state.update { it.withText(field, dictationBaseText + event.text) }
                    is TranscriptEvent.Final ->
                        _state.update {
                            it.withText(field, dictationBaseText + event.text)
                                .copy(dictationField = null, dictation = DictationStatus.Idle)
                        }
                    is TranscriptEvent.Error ->
                        _state.update {
                            it.copy(dictationField = null, dictation = DictationStatus.Error(event.reason))
                        }
                }
            }
        }
    }

    override fun stopDictation() {
        dictationJob?.cancel()
        dictationJob = null
        // Keep the streamed text — it is ordinary editable text now (ADR-0018) — just leave the listening state.
        _state.update {
            if (it.dictationField != null) it.copy(dictationField = null, dictation = DictationStatus.Idle) else it
        }
    }

    override fun dictationPermissionDenied(permanentlyDenied: Boolean) {
        dictationJob?.cancel()
        dictationJob = null
        _state.update {
            it.copy(
                dictationField = null,
                dictation = if (permanentlyDenied) {
                    DictationStatus.PermissionPermanentlyDenied
                } else {
                    DictationStatus.PermissionDenied
                },
            )
        }
    }

    private fun NewState.textOf(field: DictationField): String = when (field) {
        DictationField.Title -> title
        DictationField.Notes -> notes
    }

    private fun NewState.withText(field: DictationField, text: String): NewState = when (field) {
        DictationField.Title -> copy(title = text)
        DictationField.Notes -> copy(notes = text)
    }
}

/**
 * Build the online-only create payload for the selected kind (ADR-0016). Notes map to `description`,
 * **omitted when blank** (`null`, not `""`) so the tolerant serializer drops the field rather than
 * POSTing an empty string the server rejects (FIX 1 — `explicitNulls=false` omits nulls, *not* empty
 * strings; ADR-0011/0005). The recurring kinds default to a daily recurrence in v1 (the recurrence
 * picker is a documented follow-up). An **Event** carries its chosen fixed [start] as the required
 * `complete_by` and its optional [end] as `end_time` — never an empty string (`canSubmit` gates the
 * start, so `start` is non-null here; the `?:` end-of-epoch ([Instant.DISTANT_FUTURE]) fallback is a
 * defensive last resort that a non-submittable Event never reaches). The Chore group/rotation is
 * deferred (ADR-0015).
 */
internal fun NewState.toPayload(tz: String = "UTC"): CreateItem.Payload {
    val notesOrNull = notes.ifBlank { null }
    // The pre-dated day becomes a start-of-day instant in the Active Account's zone (#74). Null stays
    // null (omitted by the tolerant serializer), so an undated create is byte-identical to before.
    val dateInstant = date?.atStartOfDayIn(runCatching { TimeZone.of(tz) }.getOrDefault(TimeZone.UTC))
    return when (selectedKind) {
        ItemKind.Task -> CreateItem.Payload.Task(
            CreateTaskPayload(title = title.trim(), description = notesOrNull, completeBy = dateInstant?.toString()),
        )
        ItemKind.Habit -> CreateItem.Payload.Habit(
            CreateHabitPayload(
                title = title.trim(),
                recurrence = RecurrenceDto(type = "daily"),
                description = notesOrNull,
                completeBy = dateInstant?.toString(),
            ),
        )
        ItemKind.Chore -> CreateItem.Payload.Chore(
            CreateChorePayload(
                title = title.trim(),
                recurrence = RecurrenceDto(type = "daily"),
                description = notesOrNull,
                completeBy = dateInstant?.toString(),
            ),
        )
        ItemKind.Event -> CreateItem.Payload.Event(
            CreateEventPayload(
                title = title.trim(),
                // The required, non-empty start: an explicit start, else the pre-dated day, else a
                // defensive far-future fallback a non-submittable Event never reaches (`canSubmit`).
                completeBy = (start ?: dateInstant ?: Instant.DISTANT_FUTURE).toString(),
                endTime = end?.toString(),
                description = notesOrNull,
            ),
        )
    }
}
