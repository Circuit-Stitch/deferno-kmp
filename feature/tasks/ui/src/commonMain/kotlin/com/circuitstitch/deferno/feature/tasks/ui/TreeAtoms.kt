package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind

// Tasks-specific design helpers for the "See the trees" restyle. The reusable atoms (SectionLabel,
// TreeChip, KindDot, ProgressBarThin, CheckDot, SearchBarDisplay, SegmentedFilter, DashedAddButton,
// MonoMeta, Eyebrow, DefernoIcons, …) now live in the shared design system —
// com.circuitstitch.deferno.core.designsystem.component — so the Tasks Views import them from there.
// Only the kind→colour/label mapping + the tree rail are feature-local (they know the Item tree), so
// they stay here.

/** The four equal Item kinds (ADR-0034) each carry a calm colour; reinforcement, never the sole signal. */
@Composable
internal fun kindColor(kind: ItemKind): Color = when (kind) {
    ItemKind.Task -> MaterialTheme.colorScheme.primary
    ItemKind.Habit -> MaterialTheme.defernoColors.success
    ItemKind.Event -> MaterialTheme.colorScheme.secondary
    ItemKind.Chore -> MaterialTheme.colorScheme.tertiary
}

/** The plain, upper-case label for a kind, e.g. "TASK" — used as a TreeChip marker. */
internal fun kindLabel(kind: ItemKind): String = kind.name.uppercase()

/** The rail reads as a calm tint of the row's accent (#231), not a loud line — apply to the kind colour. */
internal const val RailTintAlpha = 0.5f

/** Per-depth indent each level of nesting adds — the width of one rail gutter column. */
internal val RailGutterWidth = 24.dp
private val RailStroke = 1.6.dp
private val RailInset = 11.dp // x of the vertical line within its column
private val RailElbowRadius = 9.dp

/**
 * Draws the curvy connecting rail that hangs a tree row off its parent ("See the trees", #231) and
 * indents the row's content past it. The rail is painted on the **row's own canvas** (so it gets the
 * real row height — a continuous vertical spine, not a stranded hook) in the row's accent [color]: a
 * continuous through-line for an ancestor/sibling that continues below, and a rounded elbow that
 * branches right into the row content. A depth-0 root has an empty [spine] and gets no rail/indent.
 *
 * Apply to the row's modifier (it adds the leading `start` padding for the indent itself):
 * `Modifier…​.treeRail(row.spine, kindColor(item.kind))`.
 */
internal fun Modifier.treeRail(spine: List<Boolean>, color: Color): Modifier {
    if (spine.isEmpty()) return this
    val depth = spine.size
    return this
        .drawBehind {
            val stroke = RailStroke.toPx()
            val gutter = RailGutterWidth.toPx()
            val inset = RailInset.toPx()
            val radius = RailElbowRadius.toPx()
            val midY = size.height / 2f
            val contentEdge = depth * gutter // where the indented content begins — the elbow lands here
            for (i in 0 until depth) {
                val x = i * gutter + inset
                if (i < depth - 1) {
                    // Ancestor column: a full-height through-line only when that ancestor continues below.
                    if (spine[i]) drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
                } else {
                    // The row's own column: the spine runs full-height when a sibling follows below (a
                    // "├" with a rounded branch), else only down to the corner (a rounded "└").
                    val hasFollowingSibling = spine[i]
                    drawLine(color, Offset(x, 0f), Offset(x, if (hasFollowingSibling) size.height else midY - radius), stroke)
                    drawPath(
                        Path().apply {
                            moveTo(x, midY - radius)
                            quadraticBezierTo(x, midY, x + radius, midY)
                            lineTo(contentEdge, midY)
                        },
                        color,
                        style = Stroke(width = stroke),
                    )
                }
            }
        }
        .padding(start = RailGutterWidth * depth)
}
