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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * The **New** create-surface logic (#71, ADR-0015; #185 offline-first): the explicit kind picker +
 * per-kind form state + the create dispatch. It is deliberately Compose-free so the [NewScreen] View
 * stays a thin render of this state, and so the create flow is unit-testable without a UI.
 *
 * The picker is an **explicit** Task/Habit/Chore/Event segmented choice (ADR-0015 — never inferred
 * from field content); the form adapts to [selectedKind]. Submitting routes through the shell's
 * [create] seam (the command executor's `CreateItem`). Create is now **offline-first** (#185): it
 * optimistically inserts + enqueues, so [status] settles on dismissal (created — queued, regardless of
 * connectivity); [NewStatus.Offline] is retained only as a defensive arm (the seam no longer returns it
 * for a create).
 */
interface NewComponent {
    val state: StateFlow<NewState>

    /** Pick the kind to create — the explicit segmented control (ADR-0015). */
    fun selectKind(kind: ItemKind)

    fun setTitle(title: String)
    fun setNotes(notes: String)

    /**
     * Set the item's **date** (#74) — the day the item anchors to, mapped to `complete_by` on every
     * kind: the Task/Habit/Chore deadline day, and the **Event's start day**. `null` clears it, **and
     * with it whatever clock hung off that day** ([setDeadlineTime]/[setStartTime]) — [toPayload] drops
     * a clock whose day is gone, so keeping one would leave the form showing a time that never ships.
     * This is the field the Calendar FAB pre-dates to the selected day.
     */
    fun setDate(date: LocalDate?)

    /**
     * Set the Task/Habit/Chore **deadline time-of-day** (#348) — the clock within the [setDate] day,
     * sent as `deadline_time_of_day` ("HH:MM"); `null` clears it (an all-day deadline). Ignored for an
     * Event, whose start clock is [setStartTime].
     */
    fun setDeadlineTime(time: LocalTime?)

    /**
     * Set the Event's **start time-of-day** — the clock within the [setDate] day, sent as
     * `start_time_of_day`; `null` clears it. Clearing **is** how an Event becomes all-day on its start
     * axis: `all_day` is derived read-only server-side (true iff neither time-of-day is set) and rejected
     * as input, so there is no flag to keep in sync — see [NewState.eventIsAllDay]. Ignored by the
     * non-Event kinds.
     */
    fun setStartTime(time: LocalTime?)

    /**
     * Set the Event's optional **end day** (`end_time`); `null` clears it — back to an open-ended Event,
     * which the wire accepts — **and clears [setEndTime]** with it, for [setDate]'s reason.
     */
    fun setEndDate(date: LocalDate?)

    /** Set the Event's **end time-of-day** (`end_time_of_day`) within [setEndDate]; `null` = all-day. */
    fun setEndTime(time: LocalTime?)

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

    /**
     * The [DictationStatus.PermissionPermanentlyDenied] affordance (#120): open the OS surface where
     * the person can flip the foreclosed permission. Routed to the host (the desktop deep-links the
     * blocked capability's macOS Privacy pane via live Sidecar introspection); a no-op where the View
     * owns its own deep-link (Android's app-settings intent).
     */
    fun openDictationPermissionSettings()
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

    /**
     * The permission is permanently foreclosed — the View additionally deep-links to OS settings.
     * Reached two ways: the Android View reports a "don't ask again" RECORD_AUDIO denial (#92), or the
     * engine itself settles a typed [SpeechError.PermissionDenied] via real introspection / a real
     * answered TCC prompt (#120) — a macOS denial never re-prompts, so it is permanent by definition.
     */
    data object PermissionPermanentlyDenied : DictationStatus

    /** Recognition failed (engine/capture/unavailable) — surfaced gently, never a silent failure. */
    data class Error(val reason: SpeechError) : DictationStatus
}

/** The New surface's render state. */
data class NewState(
    val selectedKind: ItemKind = ItemKind.Task,
    val title: String = "",
    val notes: String = "",
    // The WHEN axes, decomposed the way the server models them (ADR-0051, mirroring Deferno's ADR
    // 2026-06-10): every kind carries a **calendar day** plus an **optional clock**, never a fused
    // zone-bearing instant. `complete_by` is shared by all four kinds; only the clock's *name* differs,
    // because the names carry kind semantics — a Task's "when it must be done" and an Event's "when it
    // starts" are different facts that happen to share a shape.
    //
    // [date] is the `complete_by` day (#74): the Task/Habit/Chore deadline day AND the Event's start
    // day. The Calendar FAB pre-dates it to the selected day.
    val date: LocalDate? = null,
    // The Task/Habit/Chore clock within [date] → `deadline_time_of_day`. Null = all-day deadline.
    val deadlineTime: LocalTime? = null,
    // The Event's start clock within [date] → `start_time_of_day`. Null = an all-day start axis.
    val startTime: LocalTime? = null,
    // The Event's optional second axis: the end day → `end_time`, and its clock → `end_time_of_day`.
    // A null [endDate] is a valid open-ended Event. The other kinds ignore both. (No `location` field:
    // it is absent from the v0.1 contract — contracts/openapi-0.1.json carries no location anywhere —
    // so the client cannot send one; a location is a documented backend follow-up.)
    val endDate: LocalDate? = null,
    val endTime: LocalTime? = null,
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
     * Whether the Event this form would create is **all-day** — the server's own derived rule, verbatim
     * (`derive_all_day`, backend `models/event.rs:262`): all-day iff **neither axis carries a clock**.
     * There is no flag to read: `all_day` is derived read-only server-side and rejected as input, so the
     * only representation of all-dayness is the absence of [startTime] and [endTime], and a reading that
     * could disagree with what [toPayload] sends is unrepresentable. Always `false` off the Event kind,
     * whose all-day is the absence of a [deadlineTime].
     */
    val eventIsAllDay: Boolean
        get() = selectedKind == ItemKind.Event && startTime == null && endTime == null

    /**
     * Whether the Event's window closes before it opens — the one range the server rejects outright
     * (`end_time` must be `>= complete_by`, backend `models/event.rs:300`). A `null` [endDate] is a
     * *valid* open-ended Event, so this is `false` unless both edges are present and inverted.
     *
     * Each edge is compared **as the server will store it** ([atWhen] — the local day at its explicit
     * clock, or the inclusive end-of-day sentinel). That mirror is what makes the guard correct in both
     * directions, and it is load-bearing because create is offline-first (#185): there is no 400 to catch
     * the mistake, so an Event this misses is optimistically inserted, enqueued, and fails silently at
     * sync. A day-only comparison would accept an all-day start against a 09:00 end on the same day
     * (23:59:59 vs 09:00 — rejected server-side), and reject a 09:00 start against an all-day end
     * (09:00 vs 23:59:59 — accepted server-side).
     *
     * Compared as local date-times rather than instants: both edges resolve in the *same* account zone,
     * and that mapping is monotonic, so this can never accept a window the server rejects. (In a DST gap
     * two distinct local times can collapse onto one instant, where this is fractionally stricter than
     * the server — it blocks a zero-length window the server would take.)
     */
    val eventEndBeforeStart: Boolean
        get() = selectedKind == ItemKind.Event && date != null && endDate != null &&
            endDate.atWhen(endTime) < date.atWhen(startTime)

    /**
     * Create is enabled only with a non-blank title (the one universally-required field) — and, for an
     * **Event**, a start [date]: the v0.1 `POST /events` wire requires a non-empty `complete_by`
     * (ADR-0011), and a clock with no day to live on is not a start (AC #2). Its window must also not
     * close before it opens ([eventEndBeforeStart] — blocked here rather than enqueued for a create that
     * would fail at sync with nothing on screen to explain it). The recurring kinds default their cadence
     * (recurrence picker is a documented v1 follow-up).
     */
    val canSubmit: Boolean
        get() = title.isNotBlank() &&
            status != NewStatus.Submitting &&
            (selectedKind != ItemKind.Event || date != null) &&
            !eventEndBeforeStart
}

/**
 * The server's inclusive end-of-day sentinel for a clockless WHEN axis (Deferno ADR 2026-05-25;
 * `no_time_sentinel`, backend `time.rs`). 23:59:59 is never inside a DST spring-forward gap.
 */
private val NO_CLOCK_SENTINEL = LocalTime(23, 59, 59)

/**
 * A WHEN axis as the server will store it — a literal mirror of `compute_occurrence_complete_by`
 * (backend `time.rs:81`): the local day at its explicit clock, or at the inclusive end-of-day sentinel
 * when that axis carries none. THE single client-side reading of "what this day-and-clock actually
 * means", shared by the [NewState.eventEndBeforeStart] guard and by [toPayload]'s wire instants.
 */
private fun LocalDate.atWhen(clock: LocalTime?): LocalDateTime = atTime(clock ?: NO_CLOCK_SENTINEL)

/** Where the New surface is in its create lifecycle. */
sealed interface NewStatus {
    data object Editing : NewStatus
    data object Submitting : NewStatus

    /**
     * The gentle "reconnect to save"; nothing was enqueued. Since create became offline-first (#185)
     * the create seam no longer returns this — retained as a defensive arm in [submit]'s `when`.
     */
    data object Offline : NewStatus

    /** A server rejection — a typed [reason] every platform View localizes (#327). */
    data class Failed(val reason: FailedReason = FailedReason.CouldNotSaveRetry) : NewStatus {
        enum class FailedReason { CouldNotSaveRetry, CouldNotSave }
    }
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
    // The PermissionPermanentlyDenied affordance (#120), host-routed like [create]: the desktop opens
    // the blocked permission's OS settings pane; defaulted to a no-op for the View-owned hosts (Android).
    private val onOpenDictationPermissionSettings: () -> Unit = {},
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
    // Clearing a day clears the clock that hung off it — the invariant lives here, in the one place every
    // platform goes through, rather than in each View's bridge (which is how Android and desktop ended up
    // without it while the two Apple bridges each enforced their own copy).
    override fun setDate(date: LocalDate?) = _state.update {
        if (date == null) {
            it.copy(date = null, deadlineTime = null, startTime = null, status = NewStatus.Editing)
        } else {
            it.copy(date = date, status = NewStatus.Editing)
        }
    }

    override fun setEndDate(date: LocalDate?) = _state.update {
        if (date == null) {
            it.copy(endDate = null, endTime = null, status = NewStatus.Editing)
        } else {
            it.copy(endDate = date, status = NewStatus.Editing)
        }
    }

    override fun setDeadlineTime(time: LocalTime?) = _state.update { it.copy(deadlineTime = time, status = NewStatus.Editing) }
    override fun setStartTime(time: LocalTime?) = _state.update { it.copy(startTime = time, status = NewStatus.Editing) }
    override fun setEndTime(time: LocalTime?) = _state.update { it.copy(endTime = time, status = NewStatus.Editing) }

    override fun submit() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        _state.update { it.copy(status = NewStatus.Submitting) }
        launch {
            when (create(snapshot.toPayload(tz))) {
                is CommandResult.Accepted -> onCreated()
                is CommandResult.Offline -> _state.update { it.copy(status = NewStatus.Offline) }
                is CommandResult.Failed -> _state.update {
                    it.copy(status = NewStatus.Failed(NewStatus.Failed.FailedReason.CouldNotSaveRetry))
                }
                // The create gate never rejects pre-flight (CreateItem has no enabledFor rule), but be total.
                is CommandResult.Rejected -> _state.update {
                    it.copy(status = NewStatus.Failed(NewStatus.Failed.FailedReason.CouldNotSave))
                }
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
                            it.copy(
                                dictationField = null,
                                dictation = when (event.reason) {
                                    // The engine settled a real permission denial (#120 — introspected
                                    // or prompted-and-refused, never inferred from a capture failure):
                                    // terminal until flipped in OS settings, so render the deep-link
                                    // state, not a generic retry note.
                                    SpeechError.PermissionDenied -> DictationStatus.PermissionPermanentlyDenied
                                    else -> DictationStatus.Error(event.reason)
                                },
                            )
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

    override fun openDictationPermissionSettings() = onOpenDictationPermissionSettings()

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
 * Build the offline-first create payload for the selected kind (ADR-0016, #185). Notes map to
 * `description`, **omitted when blank** (`null`, not `""`) so the tolerant serializer drops the field
 * rather than POSTing an empty string the server rejects (FIX 1 — `explicitNulls=false` omits nulls,
 * *not* empty strings; ADR-0011/0005). The recurring kinds default to a daily recurrence in v1 (the
 * recurrence picker is a documented follow-up). The Chore group/rotation is deferred (ADR-0015).
 *
 * **The instants here are already normalized** (ADR-0051). `normalize_when_instant` (backend
 * `time.rs:137`) keeps only a submitted instant's *local date* and re-attaches the explicit
 * `*_time_of_day` — or the inclusive end-of-day sentinel when that is null — so the server would rewrite
 * a start-of-day instant to something else. That matters because create is offline-first: `OfflineCreateWriter`
 * stores the payload's instant **verbatim** in the local row, so sending an un-normalized one would leave
 * the row the person is looking at up to a day away from what lands server-side, silently correcting itself
 * whenever the outbox happens to drain. Pre-normalizing makes the optimistic row byte-identical to the
 * eventual truth, and is safe: the server's normalization is documented idempotent.
 */
internal fun NewState.toPayload(tz: String = "UTC"): CreateItem.Payload {
    val notesOrNull = notes.ifBlank { null }
    val zone = runCatching { TimeZone.of(tz) }.getOrDefault(TimeZone.UTC)
    /** This axis as the wire instant the server will store: the day at its clock, in the account zone. */
    fun LocalDate.wireInstant(clock: LocalTime?): Instant = atWhen(clock).toInstant(zone)
    // The deadline/start clocks ride as their own `*_time_of_day` field ("HH:MM" via LocalTime.toString()).
    // Gated on a day — a clock with no day is meaningless, and `setDate(null)` already clears them.
    val startClock = startTime
    val endClock = endTime
    val deadlineInstant = date?.wireInstant(deadlineTime)
    val deadlineWire = if (date != null) deadlineTime?.toString() else null
    val startInstant = date?.wireInstant(startClock)
    val endInstant = endDate?.wireInstant(endClock)
    return when (selectedKind) {
        ItemKind.Task -> CreateItem.Payload.Task(
            CreateTaskPayload(
                title = title.trim(),
                description = notesOrNull,
                completeBy = deadlineInstant?.toString(),
                deadlineTimeOfDay = deadlineWire,
            ),
        )
        ItemKind.Habit -> CreateItem.Payload.Habit(
            CreateHabitPayload(
                title = title.trim(),
                recurrence = RecurrenceDto(type = "daily"),
                description = notesOrNull,
                completeBy = deadlineInstant?.toString(),
                deadlineTimeOfDay = deadlineWire,
            ),
        )
        ItemKind.Chore -> CreateItem.Payload.Chore(
            CreateChorePayload(
                title = title.trim(),
                recurrence = RecurrenceDto(type = "daily"),
                description = notesOrNull,
                completeBy = deadlineInstant?.toString(),
                deadlineTimeOfDay = deadlineWire,
            ),
        )
        ItemKind.Event -> CreateItem.Payload.Event(
            CreateEventPayload(
                title = title.trim(),
                // The required, non-empty start day. `canSubmit` gates on a non-null [date], so the
                // far-future fallback is a defensive last resort a submittable Event never reaches.
                completeBy = (startInstant ?: Instant.DISTANT_FUTURE).toString(),
                endTime = endInstant?.toString(),
                // The two clocks, each independently optional. Absent ⇒ that axis is all-day, and absent
                // on BOTH is what makes the server derive `all_day` (never sent — it is derived read-only
                // server-side and rejected as input; pinned by CreatePayloadSerializationTest).
                startTimeOfDay = startClock?.toString(),
                endTimeOfDay = endClock?.toString(),
                description = notesOrNull,
            ),
        )
    }
}
