@file:OptIn(ExperimentalForeignApi::class)

package com.circuitstitch.deferno.macos

import com.circuitstitch.deferno.core.agent.Extractor
import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.feature.braindumps.BrainDumpNotifier
import com.circuitstitch.deferno.feature.braindumps.BrainDumpOutcome
import com.circuitstitch.deferno.feature.braindumps.BrainDumpPipeline
import com.circuitstitch.deferno.feature.braindumps.BrainDumpTake
import com.circuitstitch.deferno.feature.braindumps.isTrivialRecording
import com.circuitstitch.deferno.macos.speech.NativeFileTranscriber
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Swift-implemented audio recorder port (#368 Tranche 5, ADR-0037) — the macOS twin of iOS's seam of the
 * same name (#267), implemented by `BrainDump/MacBrainDumpRecorder.swift` over a real `AVAudioEngine`. Swift owns
 * the engine; Kotlin owns the WAV path and the pipeline. [start] opens the mic and streams 16-bit PCM to the
 * WAV at [filePath] (and drives the overlay spectrum); [stop] tears the mic down and finalizes the file
 * **synchronously**, so the bytes are readable the instant it returns. Only on-device audio crosses it
 * (ADR-0009/0018): the bytes go to the on-device WAV the pipeline consumes, never a log.
 *
 * **macOS divergence at the Swift half:** iOS's implementation opens with an `AVAudioSession`
 * `setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetoothHFP])` / `setActive(true)` block —
 * *`AVAudioSession` does not exist on macOS* (#368 §5).
 * There is no session to configure: input selection is `AVCaptureDevice`-based, and mic TCC comes from
 * `SidecarPermissions.requestMicrophone` (the same call `Speech/MacDictation.swift` already makes), not iOS's
 * `AVAudioApplication.requestRecordPermission`. The Kotlin contract below is unchanged by that — it is stated
 * here so a reader comparing the two Swift files doesn't read the missing session block as an oversight.
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
 * Run the shared [BrainDumpPipeline] over a finalized take (#368 Tranche 5) — the WorkManager-less macOS twin
 * of Android's `BrainDumpWorker`, and the *smaller* twin of iOS's runner (see the divergence note below).
 * [today]/[timeZone] are the captured date context; [createdAt] is the take's single instant (the retained
 * recording's key — ADR-0037).
 *
 * With no [transcriber] injected (a pre-macOS-26 Mac, or a unit host) [MacBrainDumpTake.transcribe] returns
 * blank and every non-trivial take becomes a Salvage draft; with one, the Apple `SpeechTranscriber` transcript
 * feeds the routed on-device [Extractor] and real drafts land in the Inbox. A trivially-empty take (an
 * accidental click leaves only the WAV header) is dropped here, not salvaged. The claimed WAV is deleted in
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
        // the claimed WAV in place rather than deleting it, so a sweep that raced the roster load — or a
        // later sign-in — recovers the take instead of losing it. The .processing orphan is reprocessed by a
        // later sweep; the take is never wasted (ADR-0037).
        return
    }

    // No grace window and no BGTask backstop here — a **considered divergence** from iOS (#368 §5), not an
    // omission. `UIApplication.beginBackgroundTaskWithName` is UIKit-only (there is no `platform.UIKit` klib
    // for macosArm64 at all) and `BGTaskScheduler`/`BGProcessingTaskRequest` are `API_UNAVAILABLE(macos)` (the
    // macosArm64 `BackgroundTasks` klib exposes only `BGTaskSchedulerErrorCode`). AppKit also doesn't suspend
    // a foreground app the way iOS does: the process keeps running until the person quits it. So the durable
    // claimed `.processing` WAV + the relaunch sweep **are** the whole durability story on this target — a take
    // killed mid-flight is recovered on next launch, exactly as on iOS once its backstop window has passed.
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
            take = MacBrainDumpTake(wavPath, locale, transcriber),
            today = today,
            timeZone = timeZone,
            createdAt = createdAt,
        )
    } catch (t: Throwable) {
        // Non-PII: the failure's type only, never the transcript or the audio (ADR-0009/0018). os_log-backed
        // here via the core.common facade — kmp-logger (which iOS uses) publishes no macosArm64 klib.
        Logger("BrainDump").w { "BrainDump: pipeline error (${t::class.simpleName})" }
    } finally {
        deleteFile(wavPath)
    }
}

/**
 * The notification category the Swift `UNUserNotificationCenterDelegate` recognizes to route a tap to the
 * Inbox (#271). The delegate itself (`BrainDumpNotificationDelegate` in `DefernoApp.swift`) is the Swift half
 * of #368 Tranche 5; this constant is the contract between it and [postBrainDumpCompletionNotification], and
 * the tap lands via `DefernoRoot.forwardOpenInbox`.
 */
const val BRAIN_DUMP_NOTIFICATION_CATEGORY: String = "brain-dump-complete"

/**
 * Post the opt-in Brain dump completion notification (#271) — a local notification whose tap the Swift
 * delegate routes to the Inbox. The pipeline calls this only when the person opted in (it gates on the
 * notifications pref); if OS authorization was denied, macOS simply drops the request (no crash — the take
 * still landed in the Inbox). Content mirrors Android's `notifyDraftsReady` and iOS's twin verbatim. The
 * request id keys off [createdAt] so a re-processed take replaces rather than stacks a duplicate banner.
 */
private fun postBrainDumpCompletionNotification(outcome: BrainDumpOutcome, createdAt: Instant) {
    // The uncatchable-NSException guard — see [notificationCenterAvailable]. Cheap, and always true in the
    // shipped bundle, so it costs the product path nothing.
    if (!notificationCenterAvailable()) return
    // ponytail: these three strings are user-facing yet hardcoded English — a knowing, temporary carry-over of
    // the iOS twin (app/iosApp/.../BrainDumpRecording.kt), NOT an oversight of the no-hardcoded-strings rule.
    // They cannot go through the Apple catalog from here: `L.string`/`L.plural` are Swift helpers
    // (DesignSystem/Localization.swift), and the drafts-ready body is a PLURAL whose only resolver is
    // `String.localizedStringWithFormat` — a varargs API Kotlin/Native cannot call, while
    // `NSBundle.localizedStringForKey` hands back the raw `%#@…@` wrapper for an `.xcstrings`
    // `variations.plural`. The two keys also live in the Compose catalog only
    // (`braindump_notification_drafts_ready` / `braindump_notification_recording_saved`, grandfathered
    // `android`-only in l10n-parity-overrides.txt). The fix is to move this post behind a Swift-implemented
    // notifier seam (the Swift side CAN call `L.plural`); until then the blast radius is one opt-in,
    // default-off banner, and iOS carries the identical debt.
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

/**
 * Whether this process can host `UNUserNotificationCenter` at all — **the macOS-only guard**, and the reason
 * this function exists (iOS needs none).
 *
 * The framework resolves the process's LaunchServices bundle proxy from the executable's enclosing `.app`
 * bundle; a bare binary has none, and `UNUserNotificationCenter.currentNotificationCenter()` then raises an
 * **uncatchable** NSException — an abort Kotlin/Native cannot intercept, from a stack that dead-ends inside
 * `usernoted`, and a miserable thing to diagnose. The shipped app IS bundled, so this is always true in
 * production; it is here for the unbundled hosts (a `swift build` binary, a future Kotlin unit host) where
 * the crash would otherwise be the first symptom. Same predicate as SidecarKit's `notificationCenterAvailable`
 * (helpers/macos/Sources/SidecarKit/Permissions/SidecarPermissions.swift), spelled against `bundlePath` rather
 * than Swift's `bundleURL.pathExtension` — identical for any real bundle, and it avoids an Obj-C-category
 * import here for no gain.
 */
private fun notificationCenterAvailable(): Boolean =
    NSBundle.mainBundle.bundlePath.endsWith(".app")

/**
 * The take the pipeline consumes (#368 Tranche 5): [transcribe] runs the Apple `SpeechTranscriber` over the
 * finalized WAV on-device, [readBytes] reads it for the retained recording. A blank transcript — no
 * transcriber wired (a pre-macOS-26 Mac, or a unit host), an unsupported locale, or a recognition error —
 * flows through as `""`, which the pipeline turns into a Salvage draft (the audio is never wasted, ADR-0037).
 * The Swift one-shot callback is bridged to this suspend seam via a [CompletableDeferred].
 */
private class MacBrainDumpTake(
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
        // invariant, ADR-0037). Calling back exactly once is the seam's contract, and `MacFileTranscriber`'s
        // `CallbackOnce` wrapper enforces it, so a late callback after the timeout is harmless (it completes
        // an already-orphaned Deferred).
        return withTimeoutOrNull(TRANSCRIBE_TIMEOUT) { result.await() } ?: ""
    }

    override suspend fun readBytes(): ByteArray = readFileBytes(wavPath)
}

/** A generous upper bound on one on-device file transcription — long-form recognition plus a possible
 *  one-time locale-model download — past which the take salvages rather than hangs (#368 Tranche 5). */
private val TRANSCRIBE_TIMEOUT = 120.seconds

/**
 * In-flight Brain dump WAVs live under Application Support (durable, so the relaunch sweep can recover a take
 * whose processing was killed mid-flight) — the same app-private root the attachment byte store uses.
 *
 * On macOS `NSApplicationSupportDirectory` + `NSUserDomainMask` resolves to `~/Library/Application Support`,
 * which is shared across apps — unlike iOS, where the same call is already app-private. That is deliberate and
 * it **matches**: the app is unsandboxed, and `AppleFileAttachmentBytesStore` (bound for macOS in
 * `DataBindings.macos.kt`) derives its own root exactly this way, so the in-flight WAV
 * (`deferno/braindumps/pending`) and the retained recording bytes (`deferno/attachments`) are siblings under
 * one `deferno/` root — one place to reason about, one place to wipe. So iOS's path expression ports verbatim.
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
// #270 — background durability: idempotent per-WAV claim + relaunch sweep.
// Coordination is purely through the durable pending dir: a take is a `.wav` until claimed (atomic rename
// to `.processing`), then processed and deleted. Every draft/attachment/salvage id is keyed off the take's
// createdAt (parsed from the filename), so a re-run is idempotent — a take is never lost or duplicated,
// and the relaunch sweep and the in-process run never grab the same take.
// ---------------------------------------------------------------------------------------------------

private const val WAV_SUFFIX = ".wav"
private const val PROCESSING_SUFFIX = ".processing"

/**
 * Atomically claim a pending take by renaming its WAV to the `.processing` extension; returns the claimed
 * path, or `null` if the claim was lost (another runner already moved/finished it). The atomic rename **is**
 * the per-WAV idempotent claim: only the runner that wins the rename gets a path to process, so the
 * in-process run and the relaunch sweep never grab the same fresh take. (iOS has a third claimant — its
 * BGProcessingTask backstop — which macOS does not; the claim earns its keep on both targets regardless.)
 */
internal fun claimPendingTake(wavPath: String): String? {
    val claimedPath = wavPath.removeSuffix(WAV_SUFFIX) + PROCESSING_SUFFIX
    val moved = NSFileManager.defaultManager.moveItemAtPath(wavPath, toPath = claimedPath, error = null)
    return if (moved) claimedPath else null
}

/**
 * Recover every leftover take in the pending dir — called by the relaunch sweep (after the account roster
 * loads, so [processBrainDumpTake] sees the Active Account). A fresh `.wav` is claimed then processed; a
 * `.processing` left by a run that died mid-flight is reprocessed directly. Reprocessing is idempotent (ids
 * key off the take's createdAt parsed from the filename), so even a rare double-run never duplicates a draft.
 * Each take's date context is reconstructed from its createdAt. On macOS this is the **only** recovery path
 * (iOS also runs it from its BGProcessingTask backstop — see the divergence note in [processBrainDumpTake]).
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

/**
 * The retained recording's bytes. Uses this module's established `NSData`→`ByteArray` idiom
 * (`Bridge.addTaskAttachment` / `ShellBridge.importBackup` / `ShellBridge.feedbackAddAttachment`) rather than
 * iOS's `memcpy`/`usePinned` copy — same result, one fewer interop vocabulary in this module, and no
 * `platform.posix` import. (There is no shared `NSData.toByteArray()` to reuse here: `bridge` exposes only the
 * reverse, `internal fun ByteArray.toNSData()`.)
 */
private fun readFileBytes(path: String): ByteArray {
    val data = NSData.dataWithContentsOfFile(path) ?: return ByteArray(0)
    return data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt()) ?: ByteArray(0)
}
