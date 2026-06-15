package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.common.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The **Brain dump** logic (ADR-0027, #150; Stage 4 async rework, #212 follow-on): a deliberately simple
 * voice **recorder**. It is Compose-free so the [BrainDumpScreen] View stays a thin render of this state
 * and the whole flow is unit-testable without a UI.
 *
 * The flow is: the person taps record (the View owns the RECORD_AUDIO prompt and calls [startRecording]
 * only after grant), the [record] seam captures the mic to a WAV file, [stopRecording] hands that WAV to
 * the background **Brain dump worker** (transcription + extraction survive the overlay closing), and the
 * surface shows a "transcribing in the background" note before [dismiss]. **Review no longer happens
 * here** — it moved to the **Inbox** Destination (Stage 3, ADR-0015 amendment): the worker persists the
 * proposed drafts and posts a notification, and the person triages them there. So this overlay neither
 * extracts nor creates; it only records and enqueues.
 *
 * Privacy (ADR-0009/0018/0027): the audio is never logged here — the [record] seam owns the WAV's
 * lifetime and the worker deletes it right after transcription; this component logs only structure.
 */
interface BrainDumpComponent {
    val state: StateFlow<BrainDumpState>

    /**
     * Begin recording the mic to a file. The View calls this only **after** RECORD_AUDIO is granted (it
     * owns the OS prompt). A no-op when no recorder is wired (desktop) or one is already running.
     */
    fun startRecording()

    /** Stop recording and hand the take to the background worker — the surface then shows "transcribing…". */
    fun stopRecording()

    /**
     * The View reports a **denied** RECORD_AUDIO outcome (#92) so the surface shows the gentle "needs
     * microphone access" state — and, when [permanentlyDenied], offers the OS-settings deep-link.
     */
    fun dictationPermissionDenied(permanentlyDenied: Boolean)

    /** Open the OS surface to flip a foreclosed mic permission (#120) — host-routed, a no-op by default. */
    fun openDictationPermissionSettings()

    /** Dismiss the surface (the host clears the overlay). */
    fun dismiss()
}

/** The Brain dump surface's render state — a single [phase], since the recorder has no other surface. */
data class BrainDumpState(
    val phase: Phase = Phase.Idle,
)

/** Where the Brain dump recorder is in its record → enqueue lifecycle. */
sealed interface Phase {
    /** Idle — ready to record (the start of a session). */
    data object Idle : Phase

    /** Recording the mic to a file; [stopRecording] hands it off. */
    data object Recording : Phase

    /** The take was handed to the background worker — "transcribing…", then the person dismisses. */
    data object Enqueued : Phase

    /** Recording itself failed (e.g. the mic couldn't open) — surfaced gently, with a retry. */
    data object Failed : Phase

    /** RECORD_AUDIO was denied — the gentle "needs microphone access" (the View offers a retry). */
    data object PermissionDenied : Phase

    /** The mic permission is permanently foreclosed — the View additionally deep-links to OS settings. */
    data object PermissionPermanentlyDenied : Phase
}

/**
 * Default [BrainDumpComponent]. [record] is the platform recorder seam: it suspends for the duration of
 * the recording and, when its job is **cancelled** (Stop), finalises the WAV and hands it to the
 * background worker (the Android shell closes the injected `today`/`timeZone` over it; desktop leaves it
 * the no-op default — there is no on-device engine there). [onDone] clears the overlay; [scope] runs the
 * recording job.
 */
class DefaultBrainDumpComponent(
    private val record: suspend () -> Unit = {},
    private val onDone: () -> Unit,
    private val scope: CoroutineScope,
    // The PermissionPermanentlyDenied affordance (#120), host-routed like the New surface's.
    private val onOpenDictationPermissionSettings: () -> Unit = {},
) : BrainDumpComponent {

    // Trace logger for the brain-dump path (#150). Privacy (ADR-0009/0018/0027): logs only structure —
    // phases, never any audio.
    private val log = Logger("BrainDump")

    private val _state = MutableStateFlow(BrainDumpState())
    override val state: StateFlow<BrainDumpState> = _state

    /** The active recording job; cancelling it finalises the WAV + enqueues (the [record] seam's contract). */
    private var recordJob: Job? = null

    override fun startRecording() {
        if (recordJob != null) {
            log.d { "startRecording: already recording — ignoring" }
            return
        }
        log.i { "startRecording" }
        _state.update { it.copy(phase = Phase.Recording) }
        recordJob = scope.launch {
            try {
                // Suspends until cancelled (by stopRecording / cancelCapture); on cancel the seam finalises
                // the WAV and enqueues it to the worker under NonCancellable, then re-throws.
                record()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                log.w { "record failed (${t::class.simpleName})" }
                recordJob = null
                _state.update { it.copy(phase = Phase.Failed) }
            }
        }
    }

    override fun stopRecording() {
        val job = recordJob ?: return
        log.i { "stopRecording → hand off to the worker" }
        // Cancellation is the seam's "stop" signal: the recorder finalises the WAV and enqueues it.
        job.cancel()
        recordJob = null
        _state.update { it.copy(phase = Phase.Enqueued) }
    }

    override fun dictationPermissionDenied(permanentlyDenied: Boolean) {
        recordJob?.cancel()
        recordJob = null
        _state.update {
            it.copy(phase = if (permanentlyDenied) Phase.PermissionPermanentlyDenied else Phase.PermissionDenied)
        }
    }

    override fun openDictationPermissionSettings() = onOpenDictationPermissionSettings()

    override fun dismiss() = onDone()

    /**
     * Stop recording without the "transcribing" UI — the teardown hook the shell wires to the overlay's
     * `doOnDestroy` so backing out (or an account switch) never leaves the mic hot. Cancelling still runs
     * the [record] seam's finalise + enqueue (a real take isn't lost just because the overlay closed; an
     * empty take is dropped by the seam). Idempotent.
     */
    fun cancelCapture() {
        log.d { "cancelCapture: stopping capture (teardown)" }
        recordJob?.cancel()
        recordJob = null
    }
}
