@file:OptIn(ExperimentalForeignApi::class)

package com.circuitstitch.deferno.ios

import com.circuitstitch.deferno.core.agent.Extractor
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.feature.braindumps.BrainDumpOutcome
import com.circuitstitch.deferno.feature.braindumps.BrainDumpNotifier
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
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
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
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
        // No Active Account yet (the roster may still be loading, or the person genuinely signed out): LEAVE
        // the claimed WAV in place rather than deleting it, so a sweep that raced the roster load (#270) — or a
        // later sign-in — recovers the take instead of losing it. The .processing orphan is reprocessed by a
        // later sweep; the take is never wasted (ADR-0037).
        return
    }

    // Keep the app alive briefly if the overlay closed / it backgrounded while we transcribe + persist. If the
    // grace runs out mid-flight (still backgrounded), schedule the BGProcessingTask backstop so iOS can wake us
    // to finish later (#270); the durable claimed WAV + idempotent ids make that re-run safe.
    val grace = BackgroundGrace("brain-dump", onExpired = ::scheduleBrainDumpBackstop).also { it.begin() }
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
            // Opt-in completion notification (#271): the pipeline only calls this when notifications.enabled()
            // (the NSUserDefaults pref), so it posts a local notification only when the person opted in. The
            // request id keys off createdAt so a re-processed take replaces rather than stacks.
            notifier = BrainDumpNotifier { outcome -> postBrainDumpCompletionNotification(outcome, createdAt) },
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
private class BackgroundGrace(private val name: String, private val onExpired: () -> Unit = {}) {
    private var id: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    fun begin() = onMainSync {
        id = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) {
            // The OS grace ran out while we were still working (app backgrounded): hand off to the
            // BGProcessingTask backstop before releasing the expired id (#270). Fires at most once.
            onExpired()
            endNow()
        }
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

/** The notification category the Swift `UNUserNotificationCenterDelegate` recognizes to route a tap to the Inbox (#271). */
const val BRAIN_DUMP_NOTIFICATION_CATEGORY: String = "brain-dump-complete"

/**
 * Post the opt-in Brain dump completion notification (#271) — a local notification whose tap the Swift
 * delegate routes to the Inbox. The pipeline calls this only when the person opted in (it gates on the
 * notifications pref); if OS authorization was denied, iOS simply drops the request (no crash — the take
 * still landed in the Inbox). Content mirrors Android's `notifyDraftsReady`. The request id keys off
 * [createdAt] so a re-processed take replaces rather than stacks a duplicate banner.
 */
private fun postBrainDumpCompletionNotification(outcome: BrainDumpOutcome, createdAt: Instant) {
    val body = when (outcome) {
        is BrainDumpOutcome.Drafts ->
            if (outcome.count == 1) "1 draft ready to review" else "${outcome.count} drafts ready to review"
        BrainDumpOutcome.Salvaged -> "Recording saved to review"
    }
    val content = UNMutableNotificationContent().apply {
        setTitle("Brain dump")
        setBody(body)
        setCategoryIdentifier(BRAIN_DUMP_NOTIFICATION_CATEGORY)
        setSound(UNNotificationSound.defaultSound())
    }
    val request = UNNotificationRequest.requestWithIdentifier(
        identifier = "brain-dump-${createdAt.toEpochMilliseconds()}",
        content = content,
        trigger = null,
    )
    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, withCompletionHandler = null)
}

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

// ---------------------------------------------------------------------------------------------------
// #270 — background durability: idempotent per-WAV claim + relaunch sweep + BGProcessingTask backstop.
// Coordination is purely through the durable pending dir: a take is a `.wav` until claimed (atomic rename
// to `.processing`), then processed and deleted. Every draft/attachment/salvage id is keyed off the take's
// createdAt (parsed from the filename), so a re-run is idempotent — a take is never lost or duplicated.
// ---------------------------------------------------------------------------------------------------

private const val WAV_SUFFIX = ".wav"
private const val PROCESSING_SUFFIX = ".processing"

/** The BGProcessingTask identifier — must match Info.plist `BGTaskSchedulerPermittedIdentifiers` (#270). */
const val BRAIN_DUMP_BG_TASK_ID: String = "com.circuitstitch.deferno.braindump.process"

/**
 * Atomically claim a pending take by renaming its WAV to the `.processing` extension; returns the claimed
 * path, or `null` if the claim was lost (another runner already moved/finished it). The atomic rename **is**
 * the per-WAV idempotent claim: only the runner that wins the rename gets a path to process, so the
 * in-process run, the relaunch sweep, and the BGProcessingTask backstop never grab the same fresh take.
 */
internal fun claimPendingTake(wavPath: String): String? {
    val claimedPath = wavPath.removeSuffix(WAV_SUFFIX) + PROCESSING_SUFFIX
    val moved = NSFileManager.defaultManager.moveItemAtPath(wavPath, toPath = claimedPath, error = null)
    return if (moved) claimedPath else null
}

/**
 * Recover every leftover take in the pending dir (#270) — called by the relaunch sweep (after the account
 * roster loads, so [processBrainDumpTake] sees the Active Account) and by the BGProcessingTask backstop. A
 * fresh `.wav` is claimed then processed; a `.processing` left by a run that died mid-flight is reprocessed
 * directly. Reprocessing is idempotent (ids key off the take's createdAt parsed from the filename), so even
 * a rare double-run never duplicates a draft. Each take's date context is reconstructed from its createdAt.
 */
internal suspend fun sweepPendingBrainDumps(
    appComponent: AppComponent,
    locale: String,
    transcriber: NativeFileTranscriber?,
    timeZone: String,
) {
    val zone = TimeZone.of(timeZone)
    for (path in listPendingTakes()) {
        val claimed = when {
            path.endsWith(PROCESSING_SUFFIX) -> path                  // orphan from a dead run — reprocess
            path.endsWith(WAV_SUFFIX) -> claimPendingTake(path) ?: continue  // lost the claim → skip
            else -> continue
        }
        val createdAt = parsePendingCreatedAt(claimed) ?: Clock.System.now()
        processBrainDumpTake(
            appComponent = appComponent,
            wavPath = claimed,
            locale = locale,
            transcriber = transcriber,
            today = createdAt.toLocalDateTime(zone).date,
            timeZone = timeZone,
            createdAt = createdAt,
        )
    }
}

/**
 * Schedule the BGProcessingTask backstop (#270): submitted when the in-app grace window expires while the app
 * is backgrounded, so iOS can wake the app later to finish the take in the background (the take stays a
 * durable claimed `.processing` until then, recovered by the sweep). Best-effort — a submit failure (e.g. the
 * identifier isn't permitted, or the system declines) just leaves the relaunch sweep to recover it.
 */
internal fun scheduleBrainDumpBackstop() {
    runCatching {
        val request = BGProcessingTaskRequest(BRAIN_DUMP_BG_TASK_ID).apply {
            requiresNetworkConnectivity = false
            requiresExternalPower = false
        }
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
    }
}

/** All pending take files (`braindump-<epochMs>.{wav,processing}`) under the pending dir, as absolute paths. */
private fun listPendingTakes(): List<String> {
    val dir = brainDumpPendingDir()
    val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null) ?: return emptyList()
    return names.mapNotNull { it as? String }
        .filter { it.startsWith("braindump-") && (it.endsWith(WAV_SUFFIX) || it.endsWith(PROCESSING_SUFFIX)) }
        .map { "$dir/$it" }
}

/** Parse the take's createdAt from its pending filename `braindump-<epochMs>.{wav,processing}`. */
private fun parsePendingCreatedAt(path: String): Instant? {
    val name = path.substringAfterLast('/')
    val ms = name.removePrefix("braindump-").substringBeforeLast('.').toLongOrNull() ?: return null
    return Instant.fromEpochMilliseconds(ms)
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
