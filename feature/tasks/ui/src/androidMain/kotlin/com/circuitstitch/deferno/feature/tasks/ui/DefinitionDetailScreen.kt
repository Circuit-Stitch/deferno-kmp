package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.feature.tasks.DefinitionDetailComponent

/**
 * The recurring-definition detail pane (#383), Android edition — a thin wrapper over the shared,
 * platform-neutral [DefinitionDetailContent].
 *
 * It owns **no** platform glue, and that absence is the point: the Task detail's Android wrapper exists for
 * the Storage Access Framework picker, [android.media.MediaPlayer] playback and the FAB's add sheet, all of
 * which are writes. This screen is read-only (see [DefinitionDetailContent]), so the wrapper is only the
 * state subscription — and the desktop twin, `DefinitionDetailDesktopScreen`, is the same file without the
 * AWT `FileDialog` its Task counterpart carries.
 *
 * Public so the shell can render it anywhere a definition opens, not just inside the Tasks pane.
 */
@Composable
fun DefinitionDetailScreen(
    component: DefinitionDetailComponent,
    modifier: Modifier = Modifier,
    showClose: Boolean = true,
) {
    val state by component.state.collectAsState()
    DefinitionDetailContent(
        state = state,
        onClose = component::onCloseClicked,
        modifier = modifier,
        showClose = showClose,
    )
}
