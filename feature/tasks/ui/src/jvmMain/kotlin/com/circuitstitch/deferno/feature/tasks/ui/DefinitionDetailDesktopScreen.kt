package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.feature.tasks.DefinitionDetailComponent

/**
 * The recurring-definition detail pane (#383), desktop edition — a thin wrapper over the shared
 * [DefinitionDetailContent], the twin of Android's `DefinitionDetailScreen`.
 *
 * Unlike its Task counterpart in `TasksDesktopScreen` it carries no AWT [java.awt.FileDialog] and no other
 * desktop glue: the definition detail is read-only (#383 — every write on a definition belongs to
 * #378/#388/#389), so there is nothing platform-specific left to own. The two wrappers exist only because
 * `androidMain` and `jvmMain` are separate source sets; both bodies are one call.
 *
 * Public so the desktop shell can render it anywhere a definition opens, not just inside the Tasks pane.
 */
@Composable
fun DefinitionDetailDesktopScreen(
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
