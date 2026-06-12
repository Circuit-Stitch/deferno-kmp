package com.circuitstitch.deferno.shell

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.circuitstitch.deferno.shell.ui.FeedbackForm

/**
 * The Android chrome around the shared [FeedbackForm] (#375): it owns the file-picker affordance —
 * the Storage Access Framework ([ActivityResultContracts.GetMultipleContents]) — reads each picked
 * file's bytes through the [Context.getContentResolver], and hands them to the component as
 * [FeedbackFile]s. The form itself (category/subject/body/attachments/send) is the shared atom, so the
 * Feedback overlay renders the same way Android and desktop render New (ADR-0004 #27).
 */
@Composable
fun FeedbackScreen(component: FeedbackComponent, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val files = uris.mapNotNull { readFeedbackFile(context, it) }
        if (files.isNotEmpty()) component.addAttachments(files)
    }
    FeedbackForm(
        component = component,
        onAttach = { pickFiles.launch("*/*") },
        modifier = modifier,
    )
}

/**
 * Resolve a picked [uri] into a [FeedbackFile] — its display name, MIME type, and bytes.
 * ponytail: reads bytes inline (fine for the small files feedback carries — screenshots, logs); move
 * to a background read if large uploads ever matter.
 */
private fun readFeedbackFile(context: Context, uri: Uri): FeedbackFile? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "attachment"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return FeedbackFile(filename = name, contentType = mime, bytes = bytes)
}
