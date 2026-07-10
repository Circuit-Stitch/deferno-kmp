package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_cancel
import com.circuitstitch.deferno.core.designsystem.resources.common_cannot_be_undone
import com.circuitstitch.deferno.core.designsystem.resources.common_delete
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_delete_confirm_title
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_more_actions
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_add_subtask
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_break_this_down
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import org.jetbrains.compose.resources.stringResource

/**
 * The self-contained Task-detail **⋮ more-actions overflow** (ADR-0044 §1f), driven directly by a
 * [TaskDetailComponent] so a host can drop it anywhere it holds the component: the Android shell's
 * **drilled bar** on a compact detail, and the two-pane body header slot on wide layouts. Items —
 * **Add subtask** (bumps the reveal token → the Info tab scrolls to + focuses the inline add field),
 * **Break this down** (the on-device impediment flow — a no-op on hosts that didn't wire it), and the
 * destructive **Delete** behind its own confirm dialog. "Set aside" is gone (Dropped is reachable only
 * through the status picker sheet). Icon-only trigger, so it carries its own contentDescription.
 */
@Composable
fun TaskDetailOverflowMenu(component: TaskDetailComponent, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = DefernoIcons.MoreVert,
                contentDescription = stringResource(Res.string.tasks_detail_more_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.tasks_menu_add_subtask)) },
                onClick = { expanded = false; component.onAddSubtaskRequested() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.tasks_menu_break_this_down)) },
                onClick = { expanded = false; component.onBreakdownClicked() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_delete)) },
                onClick = { expanded = false; confirmDelete = true },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.tasks_detail_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.common_cannot_be_undone)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    component.onDelete()
                }) { Text(stringResource(Res.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
    }
}
