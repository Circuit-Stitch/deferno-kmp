package com.circuitstitch.deferno.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_attach_files
import com.circuitstitch.deferno.shell.FeedbackComponent
import com.circuitstitch.deferno.shell.FeedbackFile
import com.circuitstitch.deferno.shell.ui.FeedbackForm
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop chrome around the shared [FeedbackForm] (#375): it owns the file-picker affordance —
 * an AWT [FileDialog] — reads each chosen file's bytes, and hands them to the component as
 * [FeedbackFile]s. The form itself is the shared atom, so the desktop Feedback overlay renders the
 * same way Android does (ADR-0004 #27).
 */
@Composable
fun FeedbackDesktopScreen(component: FeedbackComponent, modifier: Modifier = Modifier) {
    // Resolved in composable scope — the onAttach click callback runs outside composition.
    val attachDialogTitle = stringResource(Res.string.common_attach_files)
    FeedbackForm(
        component = component,
        onAttach = {
            val dialog = FileDialog(null as Frame?, attachDialogTitle, FileDialog.LOAD).apply {
                isMultipleMode = true
                isVisible = true // modal — blocks until the user picks or cancels
            }
            val files = dialog.files.orEmpty().mapNotNull(::readFeedbackFile)
            if (files.isNotEmpty()) component.addAttachments(files)
        },
        modifier = modifier,
    )
}

/** Read a chosen [file] into a [FeedbackFile] — name, probed MIME type, and bytes. */
private fun readFeedbackFile(file: File): FeedbackFile? {
    if (!file.isFile) return null
    val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"
    return FeedbackFile(filename = file.name, contentType = mime, bytes = file.readBytes())
}
