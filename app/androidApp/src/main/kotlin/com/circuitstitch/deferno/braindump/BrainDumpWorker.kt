package com.circuitstitch.deferno.braindump

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.circuitstitch.deferno.MainActivity
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.circuitstitch.deferno.DefernoApplication
import com.circuitstitch.deferno.core.agent.DraftTask
import com.circuitstitch.deferno.core.agent.Extractor
import com.circuitstitch.deferno.core.agent.InferenceResult
import com.circuitstitch.deferno.core.agent.Transcript
import com.circuitstitch.deferno.core.data.braindump.brainDumpRecordingPlaceholderId
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftId
import com.circuitstitch.deferno.core.model.BrainDumpDraftStatus
import com.circuitstitch.deferno.core.speech.BrainDumpTranscriber
import kotlinx.datetime.LocalDate
import software.amazon.app.kmplogger.logger
import java.io.File
import kotlin.time.Instant

/**
 * The async Brain dump worker (#150, ADR-0027). Recording is foreground (the overlay holds the mic and
 * writes a WAV via `AudioFileRecorder`); this worker runs the **slow** part — on-device transcription +
 * extraction + draft persistence — so it survives the overlay closing. It is enqueued by [enqueueBrainDump]
 * with the WAV path and the date context captured at Stop (so extraction's relative-date resolution and
 * the draft timestamps never read a clock in here — ADR `feedback-no-real-clock-dates`).
 *
 * DI: WorkManager's default reflective factory builds this; it reaches the held DI graph off
 * `(applicationContext as DefernoApplication).appComponent` — the AppScope `inferenceEngine`, and the
 * active Account's `brainDumpDraftRepository` via `createAccountComponent` (the same recipe the shell's
 * AccountSession uses). Privacy (ADR-0009/0018): the temp WAV is deleted right after transcription and no
 * audio or transcript text is logged. When the "keep brain-dump recordings" App setting is on (#211), a
 * COPY of the recording is retained as an on-device attachment placeholder (recognition still never leaves
 * the device — this is storage of the user's own content, not transcription) for the Inbox accept to attach
 * to the created Task; an empty extraction retains nothing.
 *
 * **Draft visibility (Stage 3 note):** this builds its OWN AccountComponent, so its SQLDelight driver is
 * distinct from the UI's — its `upsert`s don't fire the UI driver's live `asFlow()` listeners. That's
 * fine for the real flow (background worker → notification → the user opens the Brain dumps Destination
 * *after*, and that fresh collection's initial `selectAll()` reads the rows). The only gap is live
 * auto-refresh while the drafts screen is already open during a write — so the Stage 3 Destination should
 * re-query on resume (or observe this work's completion), not rely on a live cross-driver notification.
 */
class BrainDumpWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val wav = File(inputData.getString(KEY_WAV_PATH) ?: return Result.failure())
        val today = inputData.getString(KEY_TODAY)?.let(LocalDate::parse) ?: return done(wav)
        val timeZone = inputData.getString(KEY_TIME_ZONE) ?: return done(wav)
        val createdAt = Instant.fromEpochMilliseconds(inputData.getLong(KEY_ENQUEUED_AT, 0L))

        val app = applicationContext as DefernoApplication
        val appComponent = app.appComponent
        val account = appComponent.accountManager.activeAccount.value
            ?: return done(wav) // signed out — nothing to persist into

        return try {
            val text = BrainDumpTranscriber(applicationContext).transcribe(wav)
            if (text.isBlank()) {
                logger.i { "BrainDumpWorker: empty transcript, no drafts" }
                notifyDraftsReady(applicationContext, 0)
                return Result.success()
            }
            val proposal = when (val r = Extractor(appComponent.inferenceEngine).extract(Transcript(text), today, timeZone)) {
                is InferenceResult.Success -> r.value
                is InferenceResult.Failure -> {
                    logger.w { "BrainDumpWorker: extraction failed" }
                    return Result.success()
                }
            }
            val accountComponent = createAccountComponent(appComponent, account)
            val repo = accountComponent.brainDumpDraftRepository
            proposal.drafts.forEach { repo.upsert(it.toDraft(createdAt)) }
            logger.i { "BrainDumpWorker: persisted ${proposal.drafts.size} draft(s)" }
            // #211: retain the source recording as an on-device attachment placeholder (keyed by [createdAt],
            // the shared per-recording key) so the Inbox accept can attach it to the created Task — gated on
            // the device-local "keep recordings" App setting (default on) and only when there are drafts to
            // triage (an empty extraction has nothing to accept, so retaining would orphan). Best-effort: a
            // failed save must never lose the drafts. Recognition still never leaves the device.
            if (proposal.drafts.isNotEmpty() && appComponent.keepBrainDumpRecordingsPreference.enabled()) {
                runCatching {
                    accountComponent.localAttachmentRepository.save(
                        id = brainDumpRecordingPlaceholderId(createdAt),
                        taskId = null,
                        filename = "brain-dump-${createdAt.toEpochMilliseconds()}.wav",
                        mime = "audio/wav",
                        bytes = wav.readBytes(),
                        createdAt = createdAt,
                    )
                }
            }
            notifyDraftsReady(applicationContext, proposal.drafts.size)
            Result.success()
        } catch (e: Exception) {
            logger.w { "BrainDumpWorker: transcription/extraction error (${e::class.simpleName})" }
            Result.success() // best-effort; the audio is consumed, retrying can't help
        } finally {
            wav.delete()
        }
    }

    // Drops the temp WAV and reports success — used for the "nothing to do" exits (no account, bad input).
    private fun done(wav: File): Result {
        wav.delete()
        return Result.success()
    }
}

private fun DraftTask.toDraft(createdAt: Instant): BrainDumpDraft = BrainDumpDraft(
    id = BrainDumpDraftId(id),
    title = title,
    notes = description,
    completeBy = completeBy,
    deadlineTimeOfDay = deadlineTimeOfDay,
    status = BrainDumpDraftStatus.Ready,
    createdAt = createdAt,
)

private const val KEY_WAV_PATH = "wav_path"
private const val KEY_TODAY = "today"
private const val KEY_TIME_ZONE = "time_zone"
private const val KEY_ENQUEUED_AT = "enqueued_at"

/**
 * Enqueue an on-device Brain dump for [wav] (already recorded by the overlay). [today] and [timeZone] are
 * the threaded UI date context (NOT recomputed here); the enqueue instant is captured at this real user
 * action (the app entry, like `MainActivity` captures `today`) so the worker stays clock-free.
 */
fun enqueueBrainDump(context: Context, wav: File, today: LocalDate, timeZone: String) {
    val request = OneTimeWorkRequestBuilder<BrainDumpWorker>()
        .setInputData(
            workDataOf(
                KEY_WAV_PATH to wav.absolutePath,
                KEY_TODAY to today.toString(),
                KEY_TIME_ZONE to timeZone,
                KEY_ENQUEUED_AT to System.currentTimeMillis(),
            ),
        )
        .build()
    WorkManager.getInstance(context).enqueue(request)
}

private const val CHANNEL_ID = "brain_dump"
private const val NOTIFICATION_ID = 4150

private fun notifyDraftsReady(context: Context, count: Int) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    // minSdk 27 ≥ 26, so the channel always exists; creating it is idempotent.
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Brain dumps", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val text = when (count) {
        0 -> "No tasks found in that brain dump"
        1 -> "1 draft ready to review"
        else -> "$count drafts ready to review"
    }
    // Tapping the notification opens the app on the Inbox, where the drafts are reviewed (#150 Stage 4).
    // MainActivity is singleTop, so a running instance is reused (onNewIntent) rather than stacked.
    val openInbox = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_INBOX, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Brain dump")
        .setContentText(text)
        .setContentIntent(openInbox)
        .setAutoCancel(true)
        .build()
    // POST_NOTIFICATIONS is a runtime permission on Android 13+ (auto-granted below it); post only when
    // it's granted — the worker's result never depends on the notification landing.
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
