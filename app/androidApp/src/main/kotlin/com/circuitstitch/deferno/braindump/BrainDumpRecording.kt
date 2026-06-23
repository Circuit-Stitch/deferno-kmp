package com.circuitstitch.deferno.braindump

import android.content.Context
import com.circuitstitch.deferno.core.speech.AudioFileRecorder
import com.circuitstitch.deferno.feature.braindumps.isTrivialRecording
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import java.io.File

/**
 * The Android realisation of the Brain dump overlay's Compose-free recorder seam (ADR-0027/#150,
 * Stage 4). It records the mic to a temp WAV and, when its coroutine is **cancelled** (the overlay's
 * Stop), finalises the take and hands it to the background [BrainDumpWorker] via [enqueueBrainDump].
 *
 * The overlay component launches this in a job and cancels it on Stop; the finalise + enqueue run under
 * [NonCancellable] so closing the overlay (or an account switch) never loses or half-writes the take.
 * [today]/[timeZone] are the UI date context threaded from the shell (no `Clock.System`) — passed straight
 * to the worker for relative-date resolution. An empty take (a tap-and-immediately-stop leaves only the
 * WAV header) is dropped, not enqueued. Privacy (ADR-0009/0018): the worker owns the WAV's lifetime and
 * deletes it after transcription; nothing here logs audio. The caller must already hold `RECORD_AUDIO`.
 */
suspend fun recordBrainDumpAudio(
    context: Context,
    today: LocalDate,
    timeZone: String,
    onPcm: (FloatArray) -> Unit = {},
) {
    val wav = File.createTempFile("braindump", ".wav", context.cacheDir)
    try {
        // Suspends for the duration of the recording; on cancel it finalises the WAV, then re-throws.
        // [onPcm] is the live spectrum tap the recorder forwards each chunk to (for the listening viz).
        AudioFileRecorder().recordTo(wav, onPcm)
    } finally {
        withContext(NonCancellable) {
            // Reset the live spectrum (empty chunk → zero bars) so the visualizer settles to silence on
            // Stop instead of freezing on the last frame / leaking into the next recording.
            onPcm(FloatArray(0))
            try {
                if (!isTrivialRecording(wav.length())) {
                    enqueueBrainDump(context, wav, today, timeZone)
                } else {
                    wav.delete() // nothing was captured — don't spin up a worker for silence
                }
            } catch (t: Throwable) {
                // Handing the take to WorkManager failed (e.g. WorkManager unavailable — in which case the
                // whole app's background work is broken, not just this). The recording itself succeeded, so
                // DON'T let this bubble up and flip the overlay to "Failed"; just drop the now-unprocessable
                // WAV so it isn't orphaned in the cache. Best-effort — there is nothing here to retry.
                wav.delete()
            }
        }
    }
}
