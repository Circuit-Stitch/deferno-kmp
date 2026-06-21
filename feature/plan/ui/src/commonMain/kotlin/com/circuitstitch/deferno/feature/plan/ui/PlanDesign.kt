package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono

// Local "See the trees" design atoms for the Plan slice.
//
// ponytail: the shared atoms named in the spec (SectionLabel, Eyebrow, MonoMeta, CheckDot,
// StartPill, PrimaryActionButton, TextLink, DashedAddButton, DefernoIcons, …) are NOT present in
// core/designsystem on this branch — only the theme tokens (defernoColors, plexMono) exist. Rather
// than touch another module (the task is scoped to feature/plan/ui only), these atoms are defined
// here as thin, palette-adaptive composables over the M3 + Deferno tokens. When the real shared
// atoms land, this file can be deleted and the call sites repointed to
// com.circuitstitch.deferno.core.designsystem.component.* with no behaviour change.
//
// Decorative icons use Unicode glyphs in a Text (no material-icons dependency on this module's
// classpath, no hardcoded colour): ✦ Sparkle · ▸ Play · ✓ Check · + Plus · › Chevron · ◴ Clock.

internal const val GlyphSparkle = "✦"   // ✦
internal const val GlyphPlay = "▸"      // ▸
internal const val GlyphCheck = "✓"     // ✓
internal const val GlyphPlus = "+"
internal const val GlyphChevronRight = "›" // ›
internal const val GlyphClock = "◴"     // ◴

/** Minimum tappable target — reuses the existing [MinTouchTarget] (PlanUi.kt). */
internal val MinTouch get() = MinTouchTarget

/** Mono uppercase band header — e.g. "YOUR DAY". */
@Composable
internal fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = plexMono(),
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = modifier,
    )
}

/** Tiny mono caps eyebrow — e.g. "IF YOU'RE NOT SURE, START HERE", "FOCUSING ON". */
@Composable
internal fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.defernoColors.amberDeep,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = plexMono(),
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = modifier,
    )
}

/** Mono meta / date / count line — e.g. "MON JUN 16", "8:00 PM". */
@Composable
internal fun MonoMeta(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.defernoColors.inkMuted,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = plexMono(),
        color = color,
        modifier = modifier,
    )
}

/** A small coloured dot (kind/selection marker). */
@Composable
internal fun KindDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier = modifier.size(size).clip(CircleShape).background(color))
}

/**
 * Round "complete" checkbox. There is no completion intent on PlanComponent yet, so the caller owns
 * the (optimistic / local) [checked] state. Always carries a self-describing label for TalkBack.
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
    val scheme = MaterialTheme.colorScheme
    val success = MaterialTheme.defernoColors.success
    Box(
        modifier = modifier
            .size(MinTouch)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = contentDescription,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(2.dp, if (checked) success else scheme.outline, CircleShape)
                .background(if (checked) success else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = GlyphCheck,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.surface,
                )
            }
        }
    }
}

/** Compact "Start" pill (filled, primary). */
@Composable
internal fun StartPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String = GlyphPlay,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(scheme.primary)
            .clickable(onClickLabel = text, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = glyph, style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
        Spacer(Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
    }
}

/** Big 56dp primary action button. */
@Composable
internal fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String = GlyphPlay,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (enabled) container else scheme.surfaceVariant
    val fg = if (enabled) content else scheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled, onClickLabel = text, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleMedium, color = fg)
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

/** Inline text link in amber, with an optional trailing chevron — e.g. "See everything ›". */
@Composable
internal fun TextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.defernoColors.amberDeep,
    trailingChevron: Boolean = false,
) {
    val label = if (trailingChevron) "$text $GlyphChevronRight" else text
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = text, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

/** Dashed "Add …" affordance — e.g. "Add from the forest". */
@Composable
internal fun DashedAddButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                BorderStroke(1.dp, scheme.outlineVariant),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClickLabel = text, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = GlyphPlus, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** Small chip (optionally with a leading glyph) — e.g. "✦ Suggested". */
@Composable
internal fun TreeChip(
    text: String,
    modifier: Modifier = Modifier,
    leadingGlyph: String? = null,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    semanticLabel: String = text,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clearAndSetSemantics {
                this.role = Role.Image
                contentDescription = semanticLabel
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingGlyph != null) {
            Text(text = leadingGlyph, style = MaterialTheme.typography.labelSmall, color = content)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = plexMono(),
            color = content,
        )
    }
}

/** Title text that, when [done], renders struck-through and de-emphasised. */
internal fun TextDecorationFor(done: Boolean): TextDecoration =
    if (done) TextDecoration.LineThrough else TextDecoration.None
