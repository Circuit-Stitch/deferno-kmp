package com.circuitstitch.deferno.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The Deferno colour palettes, ported from the webui pattern library (`index.css`) into native
 * Material 3 colour schemes + the brand [DefernoColors] extension. Two palettes — `deferno`
 * (warm amber/ink) and `mono` (pure grayscale) — each in light and dark, four schemes total.
 * We align to the brand where it's stable but own the result (ADR-0010): the web's semantic
 * tokens are mapped onto M3 roles rather than ported verbatim.
 *
 * Token → role mapping (same for every palette):
 *   --paper / --paper-2 / --paper-3 / --paper-card / --paper-card-hi → background + surface ramp
 *   --ink / --ink-2 / --ink-3                                        → onBackground/onSurface ranks
 *   --amber / --amber-soft / --on-accent                            → primary family
 *   --blue / --blue-soft                                            → secondary family
 *   --violet / --violet-soft                                        → tertiary family
 *   --red / --red-soft / --on-danger                               → error family
 *   --line-soft                                                     → outline   (--line → DefernoColors.lineStrong)
 *   --green family                                                  → DefernoColors.success (no M3 role)
 */

// region raw palette tokens (hex from index.css RGB triplets)
private object DefLight {
    val paper = Color(0xFFE8E0D0); val paper2 = Color(0xFFDED4C0); val paper3 = Color(0xFFD4C9B0)
    val paperCard = Color(0xFFF2ECDC); val paperCardHi = Color(0xFFF2ECDC)
    val ink = Color(0xFF2A2620); val ink2 = Color(0xFF4A4338); val inkMute = Color(0xFF5C5340)
    val amber = Color(0xFFC97A1B); val amberDeep = Color(0xFFA05F0E); val amberSoft = Color(0xFFF4D9B0)
    val onAccent = Color(0xFFFFFFFF); val onDanger = Color(0xFFFFFFFF)
    val red = Color(0xFFB83232); val redSoft = Color(0xFFF2D2CE)
    val green = Color(0xFF3F7A3F); val greenSoft = Color(0xFFD2E3CC)
    val blue = Color(0xFF2F5C8C); val blueSoft = Color(0xFFCFDDEA)
    val violet = Color(0xFF6B4A8C); val violetSoft = Color(0xFFD9CDE3)
    val line = Color(0xFF1F1A12); val lineSoft = Color(0xFFB8AC92)
}

private object DefDark {
    val paper = Color(0xFF2A2620); val paper2 = Color(0xFF1F1B16); val paper3 = Color(0xFF3A352D)
    val paperCard = Color(0xFF2A2620); val paperCardHi = Color(0xFF3A352D)
    val ink = Color(0xFFF0E2C2); val ink2 = Color(0xFFD8CBA8); val inkMute = Color(0xFFC0B496)
    val amber = Color(0xFFE8B870); val amberDeep = Color(0xFFF0CE92); val amberSoft = Color(0xFF4A3A22)
    val onAccent = Color(0xFF1F1B14); val onDanger = Color(0xFF1F1B14)
    val red = Color(0xFFE89A8F); val redSoft = Color(0xFF4A2A26)
    val green = Color(0xFF9CC09C); val greenSoft = Color(0xFF2A4029)
    val blue = Color(0xFF99B5D2); val blueSoft = Color(0xFF2A3850)
    val violet = Color(0xFFBAA5D0); val violetSoft = Color(0xFF3A2D4A)
    val line = Color(0xFF0F0C08); val lineSoft = Color(0xFF5A5247)
}

private object MonoLight {
    val paper = Color(0xFFDDDDDD); val paper2 = Color(0xFFCCCCCC); val paper3 = Color(0xFFBBBBBB)
    val paperCard = Color(0xFFEEEEEE); val paperCardHi = Color(0xFFFFFFFF)
    val ink = Color(0xFF1A1A1A); val ink2 = Color(0xFF333333); val inkMute = Color(0xFF555555)
    val amber = Color(0xFF1A1A1A); val amberDeep = Color(0xFF000000); val amberSoft = Color(0xFFBBBBBB)
    val onAccent = Color(0xFFFFFFFF); val onDanger = Color(0xFFFFFFFF)
    val red = Color(0xFF1A1A1A); val redSoft = Color(0xFFBBBBBB)
    val green = Color(0xFF1A1A1A); val greenSoft = Color(0xFFBBBBBB)
    val blue = Color(0xFF1A1A1A); val blueSoft = Color(0xFFBBBBBB)
    val violet = Color(0xFF1A1A1A); val violetSoft = Color(0xFFBBBBBB)
    val line = Color(0xFF222222); val lineSoft = Color(0xFFAAAAAA)
}

private object MonoDark {
    val paper = Color(0xFF222222); val paper2 = Color(0xFF1A1A1A); val paper3 = Color(0xFF333333)
    val paperCard = Color(0xFF2A2A2A); val paperCardHi = Color(0xFF404040)
    val ink = Color(0xFFEEEEEE); val ink2 = Color(0xFFCCCCCC); val inkMute = Color(0xFFAAAAAA)
    val amber = Color(0xFFEEEEEE); val amberDeep = Color(0xFFFFFFFF); val amberSoft = Color(0xFF404040)
    val onAccent = Color(0xFF1A1A1A); val onDanger = Color(0xFF1A1A1A)
    val red = Color(0xFFEEEEEE); val redSoft = Color(0xFF404040)
    val green = Color(0xFFEEEEEE); val greenSoft = Color(0xFF404040)
    val blue = Color(0xFFEEEEEE); val blueSoft = Color(0xFF404040)
    val violet = Color(0xFFEEEEEE); val violetSoft = Color(0xFF404040)
    val line = Color(0xFF000000); val lineSoft = Color(0xFF555555)
}
// endregion

/**
 * Builds an M3 [ColorScheme] from a palette's semantic tokens. Every standard role is supplied,
 * so `lightColorScheme` is used as the base for both light and dark palettes — its only remaining
 * job is to default the rarely-used `*Fixed` roles, which the brand doesn't define. The surface
 * "container" ramp is ordered lowest→highest by elevation, flipped for dark (where higher = lighter).
 * Container `on*` roles use [ink] because soft containers track the surface brightness in each mode.
 */
private fun scheme(
    dark: Boolean,
    paper: Color, paper2: Color, paper3: Color, paperCard: Color, paperCardHi: Color,
    ink: Color, ink2: Color,
    amber: Color, amberSoft: Color, onAccent: Color,
    blue: Color, blueSoft: Color, violet: Color, violetSoft: Color,
    red: Color, redSoft: Color, onDanger: Color,
    lineSoft: Color,
): ColorScheme = lightColorScheme(
    primary = amber, onPrimary = onAccent, primaryContainer = amberSoft, onPrimaryContainer = ink,
    secondary = blue, onSecondary = onAccent, secondaryContainer = blueSoft, onSecondaryContainer = ink,
    tertiary = violet, onTertiary = onAccent, tertiaryContainer = violetSoft, onTertiaryContainer = ink,
    error = red, onError = onDanger, errorContainer = redSoft, onErrorContainer = ink,
    background = paper, onBackground = ink,
    surface = paper, onSurface = ink,
    surfaceVariant = paper2, onSurfaceVariant = ink2,
    surfaceTint = amber,
    surfaceDim = if (dark) paper2 else paper3,
    surfaceBright = if (dark) paper3 else paperCardHi,
    surfaceContainerLowest = if (dark) paper2 else paperCardHi,
    surfaceContainerLow = if (dark) paper else paperCard,
    surfaceContainer = if (dark) paperCard else paper,
    surfaceContainerHigh = if (dark) paper3 else paper2,
    surfaceContainerHighest = if (dark) paperCardHi else paper3,
    inverseSurface = ink, inverseOnSurface = paper, inversePrimary = amberSoft,
    outline = lineSoft, outlineVariant = paper3,
    scrim = Color.Black,
)

val DefernoLightColorScheme: ColorScheme = with(DefLight) {
    scheme(false, paper, paper2, paper3, paperCard, paperCardHi, ink, ink2, amber, amberSoft, onAccent,
        blue, blueSoft, violet, violetSoft, red, redSoft, onDanger, lineSoft)
}
val DefernoDarkColorScheme: ColorScheme = with(DefDark) {
    scheme(true, paper, paper2, paper3, paperCard, paperCardHi, ink, ink2, amber, amberSoft, onAccent,
        blue, blueSoft, violet, violetSoft, red, redSoft, onDanger, lineSoft)
}
val MonoLightColorScheme: ColorScheme = with(MonoLight) {
    scheme(false, paper, paper2, paper3, paperCard, paperCardHi, ink, ink2, amber, amberSoft, onAccent,
        blue, blueSoft, violet, violetSoft, red, redSoft, onDanger, lineSoft)
}
val MonoDarkColorScheme: ColorScheme = with(MonoDark) {
    scheme(true, paper, paper2, paper3, paperCard, paperCardHi, ink, ink2, amber, amberSoft, onAccent,
        blue, blueSoft, violet, violetSoft, red, redSoft, onDanger, lineSoft)
}

val DefernoLightBrand: DefernoColors = with(DefLight) {
    DefernoColors(green, onAccent, greenSoft, ink, amberDeep, inkMute, line)
}
val DefernoDarkBrand: DefernoColors = with(DefDark) {
    DefernoColors(green, onAccent, greenSoft, ink, amberDeep, inkMute, line)
}
val MonoLightBrand: DefernoColors = with(MonoLight) {
    DefernoColors(green, onAccent, greenSoft, ink, amberDeep, inkMute, line)
}
val MonoDarkBrand: DefernoColors = with(MonoDark) {
    DefernoColors(green, onAccent, greenSoft, ink, amberDeep, inkMute, line)
}
