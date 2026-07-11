package com.circuitstitch.deferno.core.designsystem.format

import androidx.compose.runtime.Composable
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_status_done
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_progress
import com.circuitstitch.deferno.core.designsystem.resources.common_status_in_review
import com.circuitstitch.deferno.core.designsystem.resources.tasks_set_aside
import com.circuitstitch.deferno.core.designsystem.resources.tasks_working_state_open
import org.jetbrains.compose.resources.stringResource

/**
 * A localized label for a Task working-state **wire token** (`open`/`in-progress`/`in-review`/`done`/
 * `dropped`) — the shared status formatter the Activity + Trail change diffs use to render a captured
 * `status` value (#260). Reuses the editor's status vocabulary; an unrecognised token degrades to itself.
 */
@Composable
fun activityStatusLabel(wireToken: String): String = when (wireToken) {
    "open" -> stringResource(Res.string.tasks_working_state_open)
    "in-progress" -> stringResource(Res.string.common_status_in_progress)
    "in-review" -> stringResource(Res.string.common_status_in_review)
    "done" -> stringResource(Res.string.common_status_done)
    "dropped" -> stringResource(Res.string.tasks_set_aside)
    else -> wireToken
}
