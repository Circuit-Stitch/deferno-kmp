package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.sp
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind

// Tasks-specific design helpers for the "See the trees" restyle. The reusable atoms (SectionLabel,
// TreeChip, KindDot, ProgressBarThin, CheckDot, SearchBarDisplay, SegmentedFilter, DashedAddButton,
// MonoMeta, Eyebrow, DefernoIcons, …) now live in the shared design system —
// com.circuitstitch.deferno.core.designsystem.component — so the Tasks Views import them from there.
// Only the kind→colour/label mapping + the tree rail/node are feature-local (they know the Item tree), so
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

// The rail + node geometry is sized in **sp**, not dp, so the whole tree glyph (lines, kind dot, fold
// chevron) scales with the user's font-size setting and never crops the title at large scales (#231
// follow-up). `TextUnit.toPx()` / `toDp()` both fold in `fontScale`, so one set of sp constants drives
// both the Canvas geometry (DrawScope is a Density) and the Compose layout sizes.
private val RailGutter = 22.sp // per-depth indent — the width of one rail column
private val RailStroke = 1.6.sp
private val BranchNodeDiameter = 24.sp // the larger fold-affordance disc; also the reserved node column
private val RailInset = 12.sp // == BranchNodeDiameter/2: a column's vertical line + the node's dot share this x
private val RailElbowRadius = 8.sp

private val LeafDotDiameter = 11.sp
private val NodeRing = 2.5.sp // surface ring that punches the node out of the line
private val NodeChevron = 15.sp
private val NodeTrailingGap = 8.sp
private const val BranchDiscAlpha = 0.18f // the calm accent-tinted disc behind a branch's chevron

/**
 * Draws the curvy connecting rail that hangs a tree row off its parent ("See the trees", #231) and indents
 * the row's content past it. The rail is painted on the **row's own canvas** (so it gets the real row
 * height — a continuous vertical spine, not a stranded hook; a child in a `LazyColumn` can't measure that
 * height itself) in the row's accent [color]: a continuous through-line for an ancestor/sibling that
 * continues below, and a rounded elbow that lands at the row's content edge.
 *
 * The two flags wire the rail into the connected [TreeNode] glyph for the main Item tree:
 *  - [connectToDot] extends the elbow past the content edge to the node's dot centre ([RailInset]), so the
 *    line lands **in** the dot rather than dead-ending beside it.
 *  - [descendToChildren] (an expanded parent) drops a vertical from the dot to the row's bottom, where it
 *    meets the first child's through-line just below — so a parent visibly merges with its subtree.
 * Both default off, leaving the calmer detail-outline rail (which has its own check dot) untouched.
 *
 * A depth-0 root has an empty [spine] and no indent; it still draws when [descendToChildren] is set (a root
 * parent must reach down to its children). All geometry is sp-scaled (see the constants above).
 */
internal fun Modifier.treeRail(
    spine: List<Boolean>,
    color: Color,
    connectToDot: Boolean = false,
    descendToChildren: Boolean = false,
): Modifier =
    if (spine.isEmpty() && !descendToChildren) this else composed {
        val depth = spine.size
        val indent = with(LocalDensity.current) { RailGutter.toDp() } * depth
        this
            .drawBehind {
                val stroke = RailStroke.toPx()
                val gutter = RailGutter.toPx()
                val inset = RailInset.toPx()
                val radius = RailElbowRadius.toPx()
                val midY = size.height / 2f
                val contentEdge = depth * gutter // where the indented content begins
                val dotX = contentEdge + inset // the [TreeNode] dot's centre sits on this column
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
                                lineTo(if (connectToDot) dotX else contentEdge, midY)
                            },
                            color,
                            style = Stroke(width = stroke),
                        )
                    }
                }
                // An expanded parent drops a line from its dot down to the row's bottom, merging with the
                // first child's through-line that begins just below the divider.
                if (descendToChildren) drawLine(color, Offset(dotX, midY), Offset(dotX, size.height), stroke)
            }
            .padding(start = indent)
}

/**
 * The connected tree-node glyph (#231 follow-up) that replaces the old disjoint `[chevron gutter | kind
 * dot]`: it sits on the rail column ([RailInset]), so the rail (drawn by [treeRail]) lands its elbow
 * straight into the dot and — for an expanded parent — drops a vertical from it down to the subtree. The
 * node itself is the kind dot, **merged** with the fold chevron for a parent (a leaf is a small solid kind
 * dot; a parent is an accent-tinted disc holding a chevron that rotates ▸→▾ as it folds). A surface
 * [ringColor] ring punches the node cleanly out of the line. All sp-sized, so the glyph scales with the
 * font setting.
 *
 * a11y: a parent's node is a real **Button** — focusable for keyboard / switch access and labelled
 * "Expand/Collapse {title}" with an Expanded/Collapsed state for TalkBack. A leaf (or any node in
 * [inMoveMode], where the list goes calm) is decorative and carries no semantics. The reserved node column
 * is always the branch diameter so leaf dots and parent discs share a centre and titles stay aligned.
 */
@Composable
internal fun TreeNode(
    hasChildren: Boolean,
    isExpanded: Boolean,
    title: String,
    accent: Color,
    ringColor: Color,
    inMoveMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val branchD = with(density) { BranchNodeDiameter.toDp() }
    val leafD = with(density) { LeafDotDiameter.toDp() }
    val ring = with(density) { NodeRing.toDp() }
    val chevron = with(density) { NodeChevron.toDp() }
    val trailing = with(density) { NodeTrailingGap.toDp() }
    val angle by animateFloatAsState(if (isExpanded) 90f else 0f, label = "fold")
    val interactive = hasChildren && !inMoveMode

    Box(
        modifier = modifier
            .padding(end = trailing)
            .size(branchD)
            .then(
                if (interactive) {
                    Modifier
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (isExpanded) "Collapse $title" else "Expand $title",
                            onClick = onToggle,
                        )
                        .focusable()
                        .semantics { stateDescription = if (isExpanded) "Expanded" else "Collapsed" }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (hasChildren) {
            // A parent: an accent-tinted disc holding the fold chevron, ringed in the row's surface.
            Box(
                Modifier.size(branchD).clip(CircleShape).background(ringColor),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(branchD - ring * 2).clip(CircleShape).background(accent.copy(alpha = BranchDiscAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DefernoIcons.ChevronRight,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(chevron).rotate(angle),
                    )
                }
            }
        } else {
            // A leaf: a small solid kind dot, ringed so the rail reads as landing in it.
            Box(
                Modifier.size(leafD + ring * 2).clip(CircleShape).background(ringColor),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(leafD).clip(CircleShape).background(accent))
            }
        }
    }
}
