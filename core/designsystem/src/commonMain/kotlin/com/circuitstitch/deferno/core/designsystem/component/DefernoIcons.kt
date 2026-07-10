package com.circuitstitch.deferno.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of line/glyph icons the "See the trees" design uses, translated from the design
 * canvas's SVG paths into [ImageVector]s. We build them here (rather than pull `material-icons`)
 * because `core/designsystem` carries no icons dependency — and a few of these (the 5-point
 * "suggestion" sparkle, the play triangle) match the brand's voice better than the M3 stock set.
 *
 * Each vector's intrinsic colour is black; render through `androidx.compose.material3.Icon`, whose
 * `tint` ColorFilter recolours the whole glyph (fill and stroke alike) to the current content colour
 * or whatever you pass — so these adapt to palette/light-dark for free.
 */
object DefernoIcons {

    /** The 5-point "suggestion" star — `IF YOU'RE NOT SURE, START HERE` and suggested-row marker. */
    val Sparkle: ImageVector by lazy {
        fill("Sparkle") {
            moveTo(12f, 3.2f)
            lineToRelative(2.5f, 5.2f); lineToRelative(5.7f, 0.8f); lineToRelative(-4.1f, 4f)
            lineToRelative(1f, 5.7f); lineToRelative(-5.1f, -2.7f); lineToRelative(-5.1f, 2.7f)
            lineToRelative(1f, -5.7f); lineToRelative(-4.1f, -4f); lineToRelative(5.7f, -0.8f)
            close()
        }
    }

    /** The "Start" play triangle — the design's consistent primary-action verb. */
    val Play: ImageVector by lazy {
        fill("Play") {
            moveTo(7f, 5.5f); verticalLineToRelative(13f); lineToRelative(11f, -6.5f); close()
        }
    }

    /** A bare checkmark (completion). */
    val Check: ImageVector by lazy {
        stroke("Check", width = 2.4f) {
            moveTo(5f, 12.5f); lineToRelative(4.2f, 4.2f); lineTo(19f, 7f)
        }
    }

    /** Plus — add a tree to today / a subtask / a grove. */
    val Plus: ImageVector by lazy {
        stroke("Plus") {
            moveTo(12f, 5f); verticalLineToRelative(14f)
            moveTo(5f, 12f); horizontalLineToRelative(14f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        stroke("ChevronRight") { moveTo(9f, 6f); lineToRelative(6f, 6f); lineToRelative(-6f, 6f) }
    }

    /** An X — dismiss / close (e.g. the two-pane Task-detail close affordance, ADR-0044). */
    val Close: ImageVector by lazy {
        stroke("Close") {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    val ChevronLeft: ImageVector by lazy {
        stroke("ChevronLeft") { moveTo(15f, 6f); lineToRelative(-6f, 6f); lineToRelative(6f, 6f) }
    }

    val ChevronDown: ImageVector by lazy {
        stroke("ChevronDown") { moveTo(6f, 9f); lineToRelative(6f, 6f); lineToRelative(6f, -6f) }
    }

    val Menu: ImageVector by lazy {
        stroke("Menu") {
            moveTo(3f, 6f); horizontalLineToRelative(18f)
            moveTo(3f, 12f); horizontalLineToRelative(18f)
            moveTo(3f, 18f); horizontalLineToRelative(18f)
        }
    }

    /** Vertical three-dot overflow ("⋮") — the detail's more-actions kebab. Each dot is an r2 circle. */
    val MoreVert: ImageVector by lazy {
        fill("MoreVert") {
            dot(12f, 5f); dot(12f, 12f); dot(12f, 19f)
        }
    }

    /** Magnifier — the calm search affordance. */
    val Search: ImageVector by lazy {
        stroke("Search") {
            // circle r7 @ (11,11), drawn as two relative semicircle arcs
            moveTo(18f, 11f)
            arcToRelative(7f, 7f, 0f, true, true, -14f, 0f)
            arcToRelative(7f, 7f, 0f, true, true, 14f, 0f)
            moveTo(20f, 20f); lineToRelative(-3.2f, -3.2f)
        }
    }

    /** Clock — the Focus centrepiece ring glyph. */
    val Clock: ImageVector by lazy {
        stroke("Clock", width = 1.6f) {
            moveTo(21f, 12f)
            arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
            arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
            moveTo(12f, 7.5f); verticalLineTo(12f); lineToRelative(3f, 2f)
        }
    }

    /** A filled circle of radius [r] at ([cx], [cy]) — two relative semicircle arcs (cf. [Search]). */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.dot(cx: Float, cy: Float, r: Float = 2f) {
        moveTo(cx + r, cy)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        close()
    }

    private fun fill(name: String, pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = pathBuilder)
        }.build()

    private fun stroke(
        name: String,
        width: Float = 2f,
        pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = pathBuilder,
            )
        }.build()
}
