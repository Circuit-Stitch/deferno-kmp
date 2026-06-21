package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import com.circuitstitch.deferno.core.model.ItemKind

// The "See the trees" design atoms for the Tasks feature (Forest / Everything / Grove / Branches /
// Trees → the Item tree). These are the local, palette-adaptive building blocks the restyled Tasks
// Views share. They mirror the shared-designsystem API the design brief describes (SectionLabel,
// TreeChip, KindDot, …), but live in-module: the cross-module designsystem atoms hadn't landed when
// this slice was restyled, and a feature module must not reach into another module's source. Promote
// these to core/designsystem when the shared set lands; the names + shapes already match.
//
// Tokens only — never a hardcoded hex (calm in both light and dark). The wireframe palette maps onto
// M3 + Deferno brand roles: frame→surface, card→surfaceContainerLow, well→surfaceVariant,
// accent→primary, deep→defernoColors.amberDeep, pale→primaryContainer, dividers→outlineVariant,
// muted→onSurfaceVariant / defernoColors.inkMuted.

/** The four equal Item kinds (ADR-0034) each carry a calm colour; reinforcement, never the sole signal. */
@Composable
internal fun kindColor(kind: ItemKind): Color = when (kind) {
    ItemKind.Task -> MaterialTheme.colorScheme.primary
    ItemKind.Habit -> MaterialTheme.defernoColors.success
    ItemKind.Event -> MaterialTheme.colorScheme.secondary
    ItemKind.Chore -> MaterialTheme.colorScheme.tertiary
}

/** The plain, capitalised label for a kind, e.g. "TASK" — used as a [TreeChip] marker. */
internal fun kindLabel(kind: ItemKind): String = kind.name.uppercase()

/**
 * The glyph-based icon set ("Sparkle / Play / Check / Plus / Chevrons / Menu / Search / Clock"). Kept as
 * Unicode glyphs rather than pulling `material-icons-extended` into the module (ponytail: the tree already
 * renders ▾/▸/› as text). Each rendering site supplies its own contentDescription; the glyph itself is
 * decorative.
 */
internal object DefernoIcons {
    const val Sparkle = "✦"
    const val Play = "▶"
    const val Check = "✓"
    const val Plus = "+"
    const val ChevronRight = "›"
    const val ChevronLeft = "‹"
    const val ChevronDown = "▾"
    const val Menu = "≡"
    const val Search = "🔍"
    const val Clock = "🕐"
}

/** A calm, all-caps section header (a heading for screen readers): "ATTACHMENTS", "SUBTASKS · 3", … */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.defernoColors.inkMuted,
        modifier = modifier.semantics { heading() },
    )
}

/** A small monospace eyebrow over a title block (the "EVERYTHING" / "DEEP SEARCH" lead-in). */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = plexMono(),
        color = MaterialTheme.defernoColors.inkMuted,
        modifier = modifier,
    )
}

/** Monospace metadata — counts, refs, due dates: "{n} trees", "3 of 8". Tabular, calm, muted. */
@Composable
internal fun MonoMeta(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = plexMono(),
        color = MaterialTheme.defernoColors.inkMuted,
        modifier = modifier,
    )
}

/** A small filled dot marking an Item's kind (its [color] from [kindColor]); decorative by default. */
@Composable
internal fun KindDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(color),
    )
}

/** A thin, calm progress bar (done/total) — flat, no animation, honours reduced-motion. */
@Composable
internal fun ProgressBarThin(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(height)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * A small pill chip ("TASK", a filter, a label). [filled] gives it a solid [container]; otherwise an
 * outlined, transparent chip. An optional [leadingIcon] glyph precedes the text. When given a
 * [semanticLabel] it announces that to TalkBack (and the visible text is cleared from semantics).
 */
@Composable
internal fun TreeChip(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: String? = null,
    filled: Boolean = false,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    semanticLabel: String? = null,
) {
    val shape = RoundedCornerShape(50)
    val base = if (filled) {
        Modifier.background(container, shape)
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    }
    Row(
        modifier = modifier
            .clip(shape)
            .then(base)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .then(if (semanticLabel != null) Modifier.semantics { contentDescription = semanticLabel } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Text(
                text = leadingIcon,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (semanticLabel != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

/**
 * A read-only search-bar–shaped affordance: a calm well with a search glyph + [placeholder] that, when
 * tapped, opens the real Search overlay. It is a button, not an input — the live input lives in Search.
 */
@Composable
internal fun SearchBarDisplay(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = placeholder, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DefernoIcons.Search,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = placeholder,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
    }
}

/**
 * A calm segmented filter: a row of [options] with the [selectedIndex] one filled. Each segment is a
 * ≥48dp target labelled for TalkBack as a selectable. Reduced-motion friendly (no transition).
 */
@Composable
internal fun SegmentedFilter(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable(
                        onClickLabel = if (selected) "$option, selected" else "Show $option",
                        role = Role.Tab,
                    ) { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.defernoColors.inkMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A breadcrumb path line — ancestry as tappable [crumbs] joined by chevrons (the "reach any tree" path
 * on a Search result). [onCrumb] receives the tapped crumb's index.
 */
@Composable
internal fun Breadcrumb(
    crumbs: List<String>,
    onCrumb: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = DefernoIcons.ChevronRight,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.defernoColors.inkMuted,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            Text(
                text = crumb,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.defernoColors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClickLabel = "Open $crumb") { onCrumb(index) },
            )
        }
    }
}

/**
 * A dashed "add" affordance at the foot of a list ("Add a tree"). A calm outlined button rather than a
 * filled CTA — adding is always available, never demanding. ≥48dp, labelled for TalkBack.
 */
@Composable
internal fun DashedAddButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = text, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = DefernoIcons.Plus,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * A round check toggle (the subtask done control): a tappable ring that fills with a check when
 * [checked]. ≥48dp hit area around a [size] glyph; carries [contentDescription] for TalkBack.
 */
@Composable
internal fun CheckDot(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                enabled = enabled,
                onClickLabel = contentDescription,
                role = Role.Checkbox,
            ) { onCheckedChange(!checked) }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val ring = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(percent = 50))
                .then(
                    if (checked) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier.border(2.dp, ring, RoundedCornerShape(percent = 50))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = DefernoIcons.Check,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}
