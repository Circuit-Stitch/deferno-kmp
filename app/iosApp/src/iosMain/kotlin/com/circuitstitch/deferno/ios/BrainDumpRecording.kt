@file:OptIn(ExperimentalForeignApi::class)

package com.circuitstitch.deferno.ios

import com.circuitstitch.deferno.core.agent.Extractor
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.feature.braindumps.BrainDumpPipeline
import com.circuitstitch.deferno.feature.braindumps.BrainDumpTake
import com.circuitstitch.deferno.feature.braindumps.isTrivialRecording
import com.circuitstitch.deferno.ios.speech.NativeFileTranscriber
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.datetime.LocalDate
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import platform.posix.memcpy
import software.amazon.app.kmplogger.logger
import kotlin.time.Instant

/**
 * The Swift-implemented audio recorder port (#267, ADR-0037) — the iOS twin of the macOS `NativeDictation`
 * seam. Swift owns the `AVAudioEngine`; Kotlin owns the WAV path and the pipeline. [start] opens the mic and
 * streams 16-bit PCM to the WAV at [filePath] (and drives the overlay spectrum); [stop] tears the mic down
 * and finalizes the file **synchronously**, so the bytes are readable the instant it returns. Only on-device
 * audio crosses it (ADR-0009/0018): the bytes go to the on-device WAV the pipeline consumes, never a log.
 *
 * [start] is fire-and-forget (it hops to the main thread), so it can't throw; it reports a failed mic open
 * (the engine couldn't start despite permission) via [onFailed] instead, which the seam turns into the
 * shared Failed state (Android parity — a recorded take is never silently lost).
 */
interface NativeAudioRecorder {
    fun start(filePath: String, onFailed: () -> Unit)
    fun stop()
}

/**
 * Run the shared [BrainDumpPipeline] over a finalized take (#267) — the WorkManager-less iOS twin of
 * Android's `BrainDumpWorker`. Wrapped in a `UIApplication` background task so processing survives the
 * overlay closing or a brief background. [today]/[timeZone] are the captured date context; [createdAt] is
 * the take's single instant (the retained recording's key — ADR-0037).
 *
 * In #267 there is no on-device STT yet ([IosBrainDumpTake.transcribe] returns blank), so every non-trivial
 * take becomes a Salvage draft; #269 swaps in the Apple `SpeechTranscriber`. A trivially-empty take (an
 * accidental tap leaves only the WAV header) is dropped here, not salvaged. The temp WAV is deleted in
 * `finally` — the pipeline has already copied the bytes into the per-Account attachment store when it retains.
 */
suspend fun processBrainDumpTake(
    appComponent: AppComponent,
    wavPath: String,
    locale: String,
    transcriber: NativeFileTranscriber?,
    today: LocalDate,
    timeZone: String,
    createdAt: Instant,
) {
    if (isTrivialRecording(fileSize(wavPath))) {
        deleteFile(wavPath) // nothing was captured — don't salvage silence
        return
    }
    val account = appComponent.accountManager.activeAccount.value ?: run {
        deleteFile(wavPath) // signed out — nothing to persist into
        return
    }

    // Keep the app alive briefly if the overlay closed / it backgrounded while we transcribe + persist.
    val grace = BackgroundGrace("brain-dump").also { it.begin() }
    try {
        val accountComponent = createAccountComponent(appComponent, account)
        val pipeline = BrainDumpPipeline(
            extractor = Extractor(appComponent.inferenceEngine),
            drafts = accountComponent.brainDumpDraftRepository::upsert,
            recordings = { id, bytes, ts ->
                accountComponent.localAttachmentRepository.save(
                    id = id,
                    taskId = null,
                    filename = "brain-dump-${ts.toEpochMilliseconds()}.wav",
                    mime = "audio/wav",
                    bytes = bytes,
                    createdAt = ts,
                )
            },
            keepRecordings = appComponent.keepBrainDumpRecordingsPreference,
            salvageCounter = appComponent.brainDumpSalvageCounter,
            notifications = appComponent.brainDumpNotificationPreference,
            // The opt-in completion notification is #271; the pref defaults off so this never fires yet.
            notifier = { },
        )
        pipeline.process(
            take = IosBrainDumpTake(wavPath, locale, transcriber),
            today = today,
            timeZone = timeZone,
            createdAt = createdAt,
        )
    } catch (t: Throwable) {
        BrainDumpLog.logger.w { "BrainDump: pipeline error (${t::class.simpleName})" }
    } finally {
        deleteFile(wavPath)
        grace.end()
    }
}

/**
 * A single-shot UIKit background-task grace window for one brain-dump take (#267): the OS grants a short
 * window to finish work after the app backgrounds. Every read/write of the task id is serialized onto the
 * **main queue** and guarded, so the OS expiration handler (fires on main) and [end] (called from the
 * pipeline's `Dispatchers.Default` scope) can never double-end the same identifier — which UIKit treats as
 * a fatal programmer error. [begin]/[end] must be called from off the main thread (they are — from the
 * Default-dispatched pipeline).
 */
private class BackgroundGrace(private val name: String) {
    private var id: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    fun begin() = onMainSync {
        id = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) { endNow() }
    }

    fun end() = onMainSync { endNow() }

    // Always on the main queue (the sole mutator of [id]); ends at most once, then forgets the id.
    private fun endNow() {
        val current = id
        if (current != UIBackgroundTaskInvalid) {
            id = UIBackgroundTaskInvalid
            UIApplication.sharedApplication.endBackgroundTask(current)
        }
    }
}

// Run [block] synchronously on the main queue. Callers are always off-main (the Default pipeline scope), so
// the dispatch_sync never deadlocks; the OS expiration handler runs on main and calls endNow() directly.
private fun onMainSync(block: () -> Unit) = dispatch_sync(dispatch_get_main_queue(), block)

// kmp-logger's `logger` is an Any-receiver extension; a tag object gives these top-level fns one ("BrainDump").
private object BrainDumpLog

/**
 * The take the pipeline consumes (#269): [transcribe] runs the Apple `SpeechTranscriber` over the finalized
 * WAV on-device, [readBytes] reads it for the retained recording. A blank transcript — no transcriber wired
 * (a unit host), an unsupported OS/locale, or a recognition error — flows through as `""`, which the pipeline
 * turns into a Salvage draft (the audio is never wasted, ADR-0037). The Swift one-shot callback is bridged to
 * this suspend seam via a [CompletableDeferred].
 */
private class IosBrainDumpTake(
    private val wavPath: String,
    private val locale: String,
    private val transcriber: NativeFileTranscriber?,
) : BrainDumpTake {
    override suspend fun transcribe(): String {
        val transcriber = transcriber ?: return ""
        val result = CompletableDeferred<String>()
        transcriber.transcribe(
            wavPath = wavPath,
            locale = locale,
            onResult = { result.complete(it) },
            onError = { result.complete("") },
        )
        // Bound the wait so an unresponsive transcriber (e.g. a stalled first-use locale-model download)
        // falls through to a blank transcript → Salvage, never hanging the take (the never-waste-input
        // invariant, ADR-0037). The Swift CallbackOnce de-dupes, so a late callback after the timeout is
        // harmless (it completes an already-orphaned Deferred).
        return withTimeoutOrNull(TRANSCRIBE_TIMEOUT) { result.await() } ?: ""
    }

    override suspend fun readBytes(): ByteArray = readFileBytes(wavPath)
}

/** A generous upper bound on one on-device file transcription — long-form recognition plus a possible
 *  one-time locale-model download — past which the take salvages rather than hangs (#269). */
private val TRANSCRIBE_TIMEOUT = 120.seconds

/**
 * In-flight Brain dump WAVs live under Application Support (durable, so #270's relaunch sweep can recover a
 * take whose processing was killed mid-flight) — the same app-private root the attachment byte store uses.
 */
private fun brainDumpPendingDir(): String {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: NSTemporaryDirectory()
    return "$base/deferno/braindumps/pending"
}

/** The durable WAV path for a take started at [createdAt] (named off the instant, like the recording id). */
fun brainDumpPendingWavPath(createdAt: Instant): String =
    "${brainDumpPendingDir()}/braindump-${createdAt.toEpochMilliseconds()}.wav"

/** Create the pending dir (idempotent) before the recorder opens the WAV. */
fun ensureBrainDumpPendingDir() {
    NSFileManager.defaultManager.createDirectoryAtPath(
        brainDumpPendingDir(),
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}

private fun fileSize(path: String): Long {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    return (attrs?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
}

internal fun deleteFile(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}

private fun readFileBytes(path: String): ByteArray =
    NSData.dataWithContentsOfFile(path)?.toByteArray() ?: ByteArray(0)

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), this@toByteArray.bytes, length) }
    }
}
