package com.circuitstitch.deferno.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_show_more
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import org.jetbrains.compose.resources.stringResource

/**
 * The reusable rich-text renderer for a synced item's description (the Task detail NOTES section). A
 * GitHub-imported PRD/issue arrives as **GitHub-Flavored Markdown** (the tracker owns the body verbatim),
 * so [MarkdownDescription] renders it — headings, bold/italic, inline code, code fences, blockquotes,
 * bullet/numbered lists, links, rules — instead of dumping the raw `**`/`>`/backtick source. It is a
 * design-system atom (this module is Android + JVM/desktop; the iOS View is SwiftUI, ADR-0003/0004).
 *
 * A long body is **clamped to the first [collapsedMaxLines] rendered lines** under a soft fade, the whole
 * block tappable ("Show more") to open the FULL description in a [MarkdownSheet] bottom sheet — the "rest,
 * shown only on tap" of the spec. The clamp is by rendered height (`collapsedMaxLines` × the body line
 * height), NOT by source lines: a GitHub paragraph is one long source line that soft-wraps to many visible
 * lines, so a source-line cap would show far more than "20 lines" on a phone. A body that fits within the
 * cap renders inline with no affordance. Throughout — inline preview and sheet alike — the text sits in a
 * [SelectionContainer] (long-press select + copy) and its links open externally (native `LinkAnnotation`).
 *
 * @param markdown the raw GFM source (CRLF-safe — the renderer normalizes).
 * @param sheetTitle optional heading for the expanded sheet (the caller passes its section label, e.g. NOTES).
 * @param collapsedMaxLines the preview cap in rendered body lines — the "first N lines" of the spec.
 */
@Composable
fun MarkdownDescription(
    markdown: String,
    modifier: Modifier = Modifier,
    sheetTitle: String? = null,
    collapsedMaxLines: Int = 20,
) {
    val density = LocalDensity.current
    // The clamp height: collapsedMaxLines × the body line height. bodyLarge carries a defined lineHeight
    // (24sp); fall back to fontSize × 1.4 if a theme leaves it Unspecified. Converted through density so it
    // tracks the user's font scale (accessibility). This is what "the first N lines" means for a block renderer.
    val bodyLarge = MaterialTheme.typography.bodyLarge
    // Guard on Sp (not just != Unspecified): a lineHeight expressed in Em can't `.toPx()`, so fall back
    // to fontSize × 1.4 (fontSize is always Sp on the M3 scale). bodyLarge's own lineHeight is 24.sp.
    val lineHeightSp = bodyLarge.lineHeight.takeIf { it.type == TextUnitType.Sp } ?: (bodyLarge.fontSize * 1.4f)
    val maxHeightPx = with(density) { lineHeightSp.toPx() } * collapsedMaxLines

    // The full content's measured height (−1 until first measured). Reset per description so a redraw of a
    // new task re-measures. `overflow` is only true once we know the content is taller than the clamp.
    var contentHeight by remember(markdown) { mutableIntStateOf(-1) }
    val overflow = contentHeight > maxHeightPx

    var showSheet by remember(markdown) { mutableStateOf(false) }
    val showMore = stringResource(Res.string.common_show_more)

    // The whole collapsed block is the tap target that opens the sheet ("tapping the truncated description"),
    // but ONLY when it actually overflows — a short description stays a plain, non-clickable selectable block.
    // A SelectionContainer sits inside either way, so long-press selection + link taps keep working.
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (overflow) Modifier.clickable(onClickLabel = showMore) { showSheet = true } else Modifier,
            ),
    ) {
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            SelectionContainer {
                MarkdownText(
                    markdown = markdown,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Measure the content with unbounded height (its true height), record it, then clamp
                        // the laid-out height to the cap when it exceeds it. Measuring unbounded is what lets
                        // us detect overflow — heightIn() alone would report the clamped size and hide it.
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
                            contentHeight = placeable.height
                            val clamped = if (placeable.height > maxHeightPx) maxHeightPx.toInt() else placeable.height
                            layout(placeable.width, clamped) { placeable.place(0, 0) }
                        },
                )
            }
            if (overflow) {
                // A soft fade to the page surface over the last rows — the "there's more below" cue.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(FadeHeight)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                            ),
                        ),
                )
            }
        }
        if (overflow) {
            // The "Show more" affordance. The parent Column already carries the tap + onClickLabel, so this
            // row is decorative for TalkBack (the whole block is announced once as the "Show more" action).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = showMore,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = DefernoIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showSheet) {
        MarkdownSheet(markdown = markdown, title = sheetTitle, onDismiss = { showSheet = false })
    }
}

/** The height of the bottom fade over a collapsed preview (≈ 1.5 body lines). */
private val FadeHeight = 36.dp

/**
 * A themed GFM markdown block — the primitive both the collapsed preview and the sheet build on. Maps the
 * renderer's colours/typography onto the Material 3 theme so it reads as calm body text. NOT wrapped in a
 * [SelectionContainer] itself — callers wrap it (both current call sites do) so selection composes once.
 * `internal`: [MarkdownDescription] is the module's one public markdown entry point; promote this only
 * when a real cross-module consumer of raw (unclamped) markdown appears.
 */
@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    // Parse SYNCHRONOUSLY (immediate = true) rather than the default deferred parse: a description is small,
    // so this costs nothing and avoids a one-frame empty flash on open — and it makes the render
    // deterministic for the JVM screenshot tests (a deferred parse would capture the empty loading state).
    val state = rememberMarkdownState(content = markdown, immediate = true)
    Markdown(
        markdownState = state,
        colors = defernoMarkdownColors(),
        typography = defernoMarkdownTypography(),
        modifier = modifier,
    )
}

/**
 * The full-description **bottom sheet** (the "rest, shown only on tap" of the spec): the entire GFM
 * description, vertically scrollable, wrapped in a [SelectionContainer] so it is long-press selectable +
 * copyable, its links live. Follows the same ModalBottomSheet shape as the detail's other sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownSheet(markdown: String, title: String?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp).semantics { heading() },
                )
            }
            SelectionContainer {
                MarkdownText(markdown, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Renderer colours mapped onto the Material 3 theme — calm body ink, hairline dividers. */
@Composable
private fun defernoMarkdownColors(): MarkdownColors = markdownColor(
    text = MaterialTheme.colorScheme.onSurface,
    dividerColor = MaterialTheme.colorScheme.outlineVariant,
)

/**
 * Renderer typography mapped onto the Material 3 type scale. Body copy sits at bodyLarge; the heading ramp
 * is deliberately **calm** — the m3 default maps `#`/`##` onto the display/headline scale, which renders a
 * task description's section headers as oversized banners. A description is body text with light structure,
 * so headings step down gently (22 → 14sp, bold→semibold) off the body style — distinct by weight, not bulk.
 */
@Composable
private fun defernoMarkdownTypography(): MarkdownTypography {
    val body = MaterialTheme.typography.bodyLarge
    fun heading(sizeSp: Int, weight: FontWeight): TextStyle =
        body.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.35f).sp, fontWeight = weight)
    return markdownTypography(
        text = body,
        paragraph = body,
        h1 = heading(22, FontWeight.Bold),
        h2 = heading(19, FontWeight.Bold),
        h3 = heading(17, FontWeight.SemiBold),
        h4 = heading(16, FontWeight.SemiBold),
        h5 = heading(15, FontWeight.SemiBold),
        h6 = heading(14, FontWeight.SemiBold),
    )
}
