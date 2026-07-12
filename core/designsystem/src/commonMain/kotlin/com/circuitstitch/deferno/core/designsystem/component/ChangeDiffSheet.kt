package com.circuitstitch.deferno.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.activity_diff_empty
import com.circuitstitch.deferno.core.designsystem.resources.activity_diff_open_item
import com.circuitstitch.deferno.core.designsystem.resources.activity_diff_value_cleared
import com.circuitstitch.deferno.core.designsystem.resources.activity_diff_value_unavailable
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import org.jetbrains.compose.resources.stringResource

/** One side (old or new) of a [DiffRow]. [Text] is a formatted value; [Cleared]/[Unavailable] render words. */
sealed interface DiffValue {
    data class Text(val value: String) : DiffValue
    data object Cleared : DiffValue
    data object Unavailable : DiffValue
}

/** One field's change: its localized [label] and the [before]/[after] sides the sheet renders as old->new. */
data class DiffRow(val label: String, val before: DiffValue, val after: DiffValue)

/**
 * The **change-detail bottom sheet** (#260) shared by the Activity destination and the Task Trail: a calm
 * old->new field diff of one recorded change. Each [DiffRow] shows its label, the struck-through old value,
 * then the new value; large text (a description edit) scrolls inside the sheet. [subtitle] carries the
 * calm meta line (source · time); [onOpenItem] — when non-null — adds an "Open item" action (omitted when
 * the viewer is already inside the item, e.g. the Trail). When [rows] is empty (a change with no captured
 * field diff) the sheet shows a quiet fallback so a tap is never a dead end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeDiffSheet(
    title: String,
    rows: List<DiffRow>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    onOpenItem: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                MonoMeta(subtitle, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(20.dp))
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(Res.string.activity_diff_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.defernoColors.inkMuted,
                )
            } else {
                rows.forEachIndexed { index, row ->
                    if (index > 0) Spacer(Modifier.height(18.dp))
                    DiffRowView(row)
                }
            }
            if (onOpenItem != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onOpenItem, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.activity_diff_open_item))
                }
            }
        }
    }
}

@Composable
private fun DiffRowView(row: DiffRow) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = row.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        DiffValueLine(row.before, isBefore = true)
        DiffValueLine(row.after, isBefore = false)
    }
}

/** One value line: old values sit muted + struck through under a "−"; the new value follows a "→". */
@Composable
private fun DiffValueLine(value: DiffValue, isBefore: Boolean) {
    val text = when (value) {
        is DiffValue.Text -> value.value
        DiffValue.Cleared -> stringResource(Res.string.activity_diff_value_cleared)
        DiffValue.Unavailable -> stringResource(Res.string.activity_diff_value_unavailable)
    }
    val isRealValue = value is DiffValue.Text
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = if (isBefore) "−" else "→",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isBefore) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isBefore) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (isBefore && isRealValue) TextDecoration.LineThrough else null,
            )
        }
    }
}
