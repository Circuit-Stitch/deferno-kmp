package com.circuitstitch.deferno.feature.tasks.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Task detail pane (#27), Android edition. Owns only the Android-specific glue around the shared,
 * platform-neutral [TaskDetailContent]: the Storage Access Framework file picker (file → bytes →
 * [AttachmentUpload]) and one-shot [MediaPlayer] playback of retained brain-dump recordings (#211). The
 * body itself — title block, working-state editor, and the four web-parity sections — is shared with the
 * desktop screen (ADR-0004 #27).
 */
@Composable
fun TaskDetailScreen(
    component: TaskDetailComponent,
    modifier: Modifier = Modifier,
    showHeaderOverflow: Boolean = true,
) {
    val state by component.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The Storage Access Framework picker owns the file → bytes glue (cf. the feedback FeedbackScreen);
    // it reads each picked file through the ContentResolver and hands them to the component.
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val files = uris.take(MaxAttachments)
            .mapNotNull { readAttachmentUpload(context, it) }
            .filter { it.bytes.size <= MaxAttachmentBytes }
        if (files.isNotEmpty()) component.onAddAttachments(files)
    }
    // #239: when this detail opens, the M3 ListDetailPaneScaffold programmatically focuses the pane
    // (ThreePaneScaffold → requestFocus). In touch mode the only Always-focusable targets are the
    // "Add…" OutlinedTextFields — chips/buttons are Focusability.SystemDefined, i.e. not focusable when
    // touched — so that focus has nowhere to land but the first text field, popping the soft keyboard
    // with nothing tapped. Put a harmless non-text Always-focusable target FIRST in the pane's focus
    // order to receive it instead; a real tap still focuses a field directly. It's 1.dp (zero-size focus
    // targets are skipped) and overlaid in the corner, so it adds no layout and nothing renders for it.
    Box(modifier) {
        Box(Modifier.size(1.dp).focusTarget())
        TaskDetailContent(
            state = state,
            showHeaderOverflow = showHeaderOverflow,
            onOpenParent = { state.parent?.let { component.onSubtaskClicked(it.id) } },
            onDelete = component::onDelete,
            onAddToPlan = component::onAddToPlanClicked,
            onSetWorkingState = component::onSetWorkingState,
            onSetDeadline = component::onSetDeadline,
            onSetLabels = component::onSetLabels,
            onToggleSubtask = component::onToggleSubtaskDone,
            onToggleSubtaskExpand = component::onToggleSubtaskExpand,
            onOpenSubtask = { component.onSubtaskClicked(it.id) },
            onAddSubtask = component::onAddSubtask,
            onSetHideDoneSubtasks = component::onSetHideDoneSubtasks,
            onPostComment = component::onPostComment,
            onEditComment = component::onEditComment,
            onDeleteComment = component::onDeleteComment,
            onAddAttachment = { pickFiles.launch("*/*") },
            onDeleteAttachment = component::onDeleteAttachment,
            onSetAttachmentCaption = component::onSetAttachmentCaption,
            onDeleteOnDeviceAttachment = component::onDeleteOnDeviceAttachment,
            // Play a retained recording (#211): read its on-device bytes, then hand them to MediaPlayer.
            onPlayOnDeviceAttachment = { att ->
                scope.launch {
                    val bytes = component.onDeviceAttachmentBytes(att.id) ?: return@launch
                    playAudioBytes(context, bytes)
                }
            },
            // "Break this down" (Deferno#525) → the host opens the on-device impediment overlay.
            onBreakdown = component::onBreakdownClicked,
        )
    }
}

/**
 * One-shot local playback of [bytes] (a retained brain-dump WAV, #211): write to a private cache file and
 * play it through [MediaPlayer], releasing on completion/error. Off the main thread (file IO + prepare); a
 * single reused temp file is fine since only one recording plays at a time. Best-effort — a playback
 * failure must never crash the detail.
 */
private suspend fun playAudioBytes(context: Context, bytes: ByteArray) = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(context.cacheDir, "braindump-play.wav")
        file.writeBytes(bytes)
        MediaPlayer().apply {
            setOnCompletionListener { it.release() }
            setOnErrorListener { mp, _, _ -> mp.release(); true }
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
    }
    Unit
}

// The web's attachment limits: at most 5 files per add, 25 MB each.
private const val MaxAttachments = 5
private const val MaxAttachmentBytes = 25 * 1024 * 1024

/** Resolve a picked [uri] into an [AttachmentUpload] — its display name, MIME type, and bytes. */
private fun readAttachmentUpload(context: Context, uri: Uri): AttachmentUpload? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "attachment"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return AttachmentUpload(filename = name, contentType = mime, bytes = bytes)
}
