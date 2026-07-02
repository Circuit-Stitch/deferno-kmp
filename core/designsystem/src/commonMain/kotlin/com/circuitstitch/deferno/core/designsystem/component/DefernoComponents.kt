package com.circuitstitch.deferno.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_blocked
import com.circuitstitch.deferno.core.designsystem.resources.common_search
import com.circuitstitch.deferno.core.designsystem.resources.common_state_done
import com.circuitstitch.deferno.core.designsystem.resources.common_state_not_done
import com.circuitstitch.deferno.core.designsystem.resources.common_state_selected
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import org.jetbrains.compose.resources.stringResource

/*
 * Shared "See the trees" design-language atoms (the Deferno Android direction). Thin, stateless,
 * palette-adaptive Composables that feature screens compose into the Today / Forest / Detail / Search
 * surfaces. They read **existing** [androidx.compose.material3.MaterialTheme] + brand
 * [com.circuitstitch.deferno.core.designsystem.theme.defernoColors] tokens — no new palette roles —
 * so they track Deferno/Mono × light/dark for free. Design-hex → token map (deferno-light shown):
 *   frame #E8E0D0 → surface · card #F2ECDC → surfaceContainerLow · chip well #DED4C0 → surfaceVariant
 *   accent #C97A1B → primary · deeper #A05F0E → defernoColors.amberDeep · pale #F4D9B0 → primaryContainer
 *   dividers/dashed #DBD0BA/#C2B79C → outlineVariant/outline · muted copy → onSurfaceVariant / inkMuted
 * Screen-specific composites (the Today suggestion banner, the Focus ring, grove/branch cards, the
 * choice cards) live in their feature modules and build on these.
 */

/** Minimum height for any tappable control — design-principles.md "≥44–48dp" touch targets. */
val MinTouchTarget = 48.dp

/**
 * A mono, upper-track section label — the recurring `YOUR DAY` / `BRANCHES` / `ALL GROVES · A–Z`
 * eyebrow that organises a screen into calm, scannable bands.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = plexMono(),
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.1.em,
    )
}

/**
 * A tiny mono eyebrow above a title — e.g. `IF YOU'RE NOT SURE, START HERE`, `FOCUSING ON`.
 * Defaults to the deep-amber accent the design uses for these hints.
 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.defernoColors.amberDeep) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = plexMono(),
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.12.em,
    )
}

/** A mono meta line — dates, counts ("5 of 22"), durations. The third, quietest text rank. */
@Composable
fun MonoMeta(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.defernoColors.inkMuted) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = plexMono(),
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A small solid status/kind dot (grove colour, kind colour). Colour is reinforcement, never sole signal. */
@Composable
fun KindDot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/**
 * The round "tap to complete" checkbox the day-list and tree rows carry. Unchecked = a hollow ring;
 * checked = a filled primary disc with a check. [enabled] = false dims it (a branch locked by open
 * subtasks). Fully labelled for TalkBack.
 */
@Composable
fun CheckDot(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val checkedState = stringResource(Res.string.common_state_done)
    val uncheckedState = stringResource(Res.string.common_state_not_done)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (checked) Modifier.background(scheme.primary)
                else Modifier.border(1.6.dp, if (enabled) scheme.outline else scheme.outlineVariant, CircleShape)
            )
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = { onCheckedChange(!checked) })
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = if (checked) checkedState else uncheckedState
            },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(DefernoIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** A thin (5dp) progress bar — a grove's done/total, a subtask outline's completion. */
@Composable
fun ProgressBarThin(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Box(
        modifier
            .height(5.dp)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * The big, thumb-reachable primary action ("Start · …", "Done — next step", "Create task"). 56dp,
 * a leading [icon], an honest button role + click label for TalkBack. [container]/[content] default
 * to the primary pair but the dark Focus surface passes gold.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = DefernoIcons.Play,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Surface(
        color = if (enabled) container else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) content else MaterialTheme.defernoColors.inkMuted,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = text, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A compact pill action (the Today suggestion's "▶ Start"). */
@Composable
fun StartPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = DefernoIcons.Play,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** An inline text link, accent-coloured, optional trailing chevron ("See everything ›", "Pick for me"). */
@Composable
fun TextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.defernoColors.amberDeep,
    trailingChevron: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick)
            .heightIn(min = MinTouchTarget)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (trailingChevron) Icon(DefernoIcons.ChevronRight, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
    }
}

/**
 * The dashed, low-key "add" affordance ("Add from the forest", a grove's "Add", "Add a subtask").
 * Calm by design — secondary text, accented plus, a dashed outline that doesn't shout.
 */
@Composable
fun DashedAddButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)),
                    ),
                )
            }
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DefernoIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A calm, **display-only** search affordance — a rounded well that opens the real search surface on
 * tap (the design's "Search all 312 trees…" / "Find in House renovation…" rows). The actual query
 * field lives in the Search screen; this keeps dense screens quiet until you reach for it.
 */
@Composable
fun SearchBarDisplay(placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.common_search), onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(DefernoIcons.Search, contentDescription = null, tint = MaterialTheme.defernoColors.inkMuted, modifier = Modifier.size(18.dp))
            Text(placeholder, color = MaterialTheme.defernoColors.inkMuted, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A segmented "how much to see" control (Everything's `In today / Active / All 31`, New task's kind
 * picker, the filter sheet's status). A pill well with the selected segment lifted onto the surface.
 */
@Composable
fun SegmentedFilter(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val selectedState = stringResource(Res.string.common_state_selected)
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Surface(
                color = if (selected) scheme.surface else Color.Transparent,
                contentColor = if (selected) scheme.onSurface else MaterialTheme.defernoColors.inkMuted,
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(role = Role.RadioButton, onClick = { onSelect(i) })
                    .semantics { if (selected) stateDescription = selectedState },
            ) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * A drill breadcrumb ("Everything › House renovation › Kitchen"), mono and quiet, the last crumb
 * emphasised as the current location. [onCrumb] is called with the index of a tapped ancestor.
 */
@Composable
fun Breadcrumb(
    crumbs: List<String>,
    modifier: Modifier = Modifier,
    onCrumb: (Int) -> Unit = {},
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        crumbs.forEachIndexed { i, crumb ->
            val current = i == crumbs.lastIndex
            Text(
                text = crumb,
                color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.defernoColors.inkMuted,
                fontFamily = plexMono(),
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (current) Modifier else Modifier.clip(RoundedCornerShape(6.dp)).clickable { onCrumb(i) }.padding(2.dp),
            )
            if (!current) {
                Icon(DefernoIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/**
 * A small rounded chip/badge ("✦ Suggested", "in today", "TASK"). [filled] uses the soft primary
 * container; otherwise an outlined chip. Decorative by default — set [semanticLabel] when the chip
 * is the only carrier of meaning.
 */
@Composable
fun TreeChip(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    filled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.defernoColors.amberDeep,
    semanticLabel: String? = null,
) {
    Surface(
        color = if (filled) container else Color.Transparent,
        contentColor = content,
        shape = CircleShape,
        border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.then(
            if (semanticLabel != null) Modifier.clearAndSetSemantics { contentDescription = semanticLabel }
            else Modifier
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(11.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The shared "Blocked" pill (#290) — a quiet outlined [TreeChip] in muted ink that marks a blocked
 * row. Single source for the string, color, and TalkBack label across every blockable surface
 * (tree / plan / search / task-detail subtasks), so they can't drift apart.
 */
@Composable
fun BlockedChip(modifier: Modifier = Modifier) {
    TreeChip(
        text = stringResource(Res.string.common_blocked),
        modifier = modifier,
        filled = false,
        content = MaterialTheme.defernoColors.inkMuted,
        semanticLabel = stringResource(Res.string.common_blocked),
    )
}
