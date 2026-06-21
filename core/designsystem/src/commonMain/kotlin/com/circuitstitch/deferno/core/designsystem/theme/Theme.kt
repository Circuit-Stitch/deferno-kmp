package com.circuitstitch.deferno.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** The two brand palettes (ADR-0010). Each renders light or dark; the switcher selects between them. */
enum class DefernoPalette { Deferno, Mono }

/**
 * The active brand [DefernoPalette] of the enclosing [DefernoTheme] — exposed so an **immersive** surface
 * (Brain dump, Focus, Move) can re-theme itself dark to match its mock while keeping the user's chosen
 * palette (Deferno vs Mono): `DefernoTheme(palette = LocalDefernoPalette.current, darkTheme = true) { … }`.
 */
val LocalDefernoPalette = staticCompositionLocalOf { DefernoPalette.Deferno }

// Slightly rounded, mobile-first shapes — gentler than the web's 4–6px corners (ADR-0010: own the
// system, don't port web tokens), while staying close to the familiar M3 scale.
private val DefernoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * The Deferno Material 3 theme. Selects one of the four palettes by [palette] × [darkTheme] and
 * provides both the M3 [androidx.compose.material3.ColorScheme] and the brand [DefernoColors]
 * extension (read via [defernoColors]). Dynamic colour (Material You) is intentionally omitted —
 * Deferno ships its own brand palettes (ADR-0010), and the design system is multiplatform.
 */
@Composable
fun DefernoTheme(
    palette: DefernoPalette = DefernoPalette.Deferno,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when (palette) {
        DefernoPalette.Deferno -> if (darkTheme) DefernoDarkColorScheme else DefernoLightColorScheme
        DefernoPalette.Mono -> if (darkTheme) MonoDarkColorScheme else MonoLightColorScheme
    }
    val brand = when (palette) {
        DefernoPalette.Deferno -> if (darkTheme) DefernoDarkBrand else DefernoLightBrand
        DefernoPalette.Mono -> if (darkTheme) MonoDarkBrand else MonoLightBrand
    }
    CompositionLocalProvider(LocalDefernoColors provides brand, LocalDefernoPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = defernoTypography(),
            shapes = DefernoShapes,
            content = content,
        )
    }
}

/**
 * The brand colour roles ([DefernoColors]) for the current [DefernoTheme] — the companion to
 * `MaterialTheme.colorScheme` for tokens M3 doesn't model (e.g. `MaterialTheme.defernoColors.success`).
 */
val MaterialTheme.defernoColors: DefernoColors
    @Composable @ReadOnlyComposable get() = LocalDefernoColors.current
