package com.circuitstitch.deferno.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brand colour roles that Material 3's [androidx.compose.material3.ColorScheme] does not model,
 * but the Deferno palette carries (ADR-0010 — the client owns its design system, aligned to the
 * brand where it's stable). Provided alongside the M3 scheme via [LocalDefernoColors] and surfaced
 * through [DefernoTheme]'s `MaterialTheme` so a Composable can read both with one theme in scope.
 *
 * Notably [success] (the web `--green` family) has no M3 equivalent; [amberDeep] is the
 * higher-contrast accent used for filled emphasis; [inkMuted] is the third text rank (web
 * `--ink-3/--ink-mute`); and [lineStrong] is the heavy separator (web `--line`, vs the M3
 * `outline` which maps to `--line-soft`).
 */
@Immutable
data class DefernoColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val amberDeep: Color,
    val inkMuted: Color,
    val lineStrong: Color,
)

/**
 * The active [DefernoColors] for the current [DefernoTheme]. `static` because the value swaps
 * wholesale with the theme (palette/light-dark), not per-recomposition. The default is the
 * deferno-light brand set so previews / a stray read outside a theme still resolve sensibly.
 */
val LocalDefernoColors = staticCompositionLocalOf { DefernoLightBrand }
