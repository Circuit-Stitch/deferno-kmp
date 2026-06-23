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
import com.circuitstitch.deferno.core.agent.Extractor
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.core.speech.BrainDumpTranscriber
import com.circuitstitch.deferno.feature.braindumps.BrainDumpNotifier
import com.circuitstitch.deferno.feature.braindumps.BrainDumpOutcome
import com.circuitstitch.deferno.feature.braindumps.BrainDumpPipeline
import com.circuitstitch.deferno.feature.braindumps.BrainDumpTake
import kotlinx.datetime.LocalDate
import software.amazon.app.kmplogger.logger
import java.io.File
import kotlin.time.Instant

/**
 * The async Brain dump worker (#150, ADR-0027/0037). Recording is foreground (the overlay holds the mic and
 * writes a WAV via `AudioFileRecorder`); this worker runs the **slow** part — on-device transcription +
 * extraction + draft persistence — so it survives the overlay closing. It is enqueued by [enqueueBrainDump]
 * with the WAV path and the date context captured at Stop (so extraction's relative-date resolution and the
 * draft timestamps never read a clock in here — ADR `feedback-no-real-clock-dates`).
 *
 * The orchestration itself lives in the shared [BrainDumpPipeline] (commonMain, reused by iOS — ADR-0037):
 * `transcribe → extract → (persist drafts | Salvage draft)`, with transcription, audio retention and the
 * completion notification injected as Android seams below. This worker is the thin WorkManager host adapter:
 * input parsing, building the DI collaborators, and deleting the temp WAV in `finally`.
 *
 * DI: WorkManager's default reflective factory builds this; it reaches the held DI graph off
 * `(applicationContext as DefernoApplication).appComponent` — the AppScope `inferenceEngine` /
 * `keepBrainDumpRecordingsPreference` / `brainDumpSalvageCounter`, and the active Account's repositories via
 * `createAccountComponent` (the same recipe the shell's AccountSession uses). Privacy (ADR-0009/0018): the
 * temp WAV is deleted right after processing and no audio or transcript text is logged.
 *
 * **Draft visibility (Stage 3 note):** this builds its OWN AccountComponent, so its SQLDelight driver is
 * distinct from the UI's — its `upsert`s don't fire the UI driver's live `asFlow()` listeners. That's fine
 * for the real flow (background worker → the user opens the Inbox *after*, whose fresh collection's initial
 * `selectAll()` reads the rows). The Inbox Destination re-queries on resume rather than relying on a live
 * cross-driver notification.
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
                notifier = AndroidBrainDumpNotifier(applicationContext),
            )
            pipeline.process(
                take = AndroidBrainDumpTake(wav, BrainDumpTranscriber(applicationContext)),
                today = today,
                timeZone = timeZone,
                createdAt = createdAt,
            )
            Result.success()
        } catch (e: Exception) {
            logger.w { "BrainDumpWorker: pipeline error (${e::class.simpleName})" }
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

/** The transcription seam: whole-file on-device transcription via [BrainDumpTranscriber] (returns "" on failure). */
private class AndroidBrainDumpTake(
    private val wav: File,
    private val transcriber: BrainDumpTranscriber,
) : BrainDumpTake {
    override suspend fun transcribe(): String = transcriber.transcribe(wav)
    override suspend fun readBytes(): ByteArray = wav.readBytes()
}

/** The notification seam: posts the local "drafts ready" / "recording saved" notification (permission-gated). */
private class AndroidBrainDumpNotifier(private val context: Context) : BrainDumpNotifier {
    override fun completed(outcome: BrainDumpOutcome) = notifyDraftsReady(context, outcome)
}

private const val KEY_WAV_PATH = "wav_path"
private const val KEY_TODAY = "today"
private const val KEY_TIME_ZONE = "time_zone"
private const val KEY_ENQUEUED_AT = "enqueued_at"

/**
 * Enqueue an on-device Brain dump for [wav] (already recorded by the overlay). [today] and [timeZone] are the
 * threaded UI date context (NOT recomputed here); the enqueue instant is captured at this real user action (the
 * app entry, like `MainActivity` captures `today`) so the worker stays clock-free.
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

private fun notifyDraftsReady(context: Context, outcome: BrainDumpOutcome) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    // minSdk 27 ≥ 26, so the channel always exists; creating it is idempotent.
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Brain dumps", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val text = when (outcome) {
        is BrainDumpOutcome.Drafts -> when (outcome.count) {
            1 -> "1 draft ready to review"
            else -> "${outcome.count} drafts ready to review"
        }
        // Salvage: the take couldn't become tasks, but the recording is kept for the user to review.
        BrainDumpOutcome.Salvaged -> "Recording saved to review"
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
