package com.circuitstitch.deferno.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The colour tokens are data (the @Composable theme/typography builders are screenshot-tested, not
 * unit-tested — ADR-0006). These guard the index.css → Material 3 mapping: exact brand values, the
 * deferno↔mono / light↔dark distinctions, and that mono is genuinely grayscale.
 */
class PaletteTest {

    @Test
    fun defernoLightMapsBrandTokens() {
        assertEquals(Color(0xFFC97A1B), DefernoLightColorScheme.primary, "primary = --amber")
        assertEquals(Color(0xFFFFFFFF), DefernoLightColorScheme.onPrimary, "onPrimary = --on-accent")
        assertEquals(Color(0xFFE8E0D0), DefernoLightColorScheme.background, "background = --paper")
        assertEquals(Color(0xFF2A2620), DefernoLightColorScheme.onBackground, "onBackground = --ink")
        assertEquals(Color(0xFFB83232), DefernoLightColorScheme.error, "error = --red")
        assertEquals(Color(0xFFB8AC92), DefernoLightColorScheme.outline, "outline = --line-soft")
    }

    @Test
    fun defernoDarkIsDistinctFromLight() {
        assertEquals(Color(0xFF2A2620), DefernoDarkColorScheme.background, "dark background = --paper")
        assertEquals(Color(0xFFE8B870), DefernoDarkColorScheme.primary, "dark primary = --amber (dark)")
        assertNotEquals(DefernoLightColorScheme.background, DefernoDarkColorScheme.background)
        assertNotEquals(DefernoLightColorScheme.onBackground, DefernoDarkColorScheme.onBackground)
    }

    @Test
    fun darkCardsCarryATonalStepAboveTheBackground() {
        // #335: the canonical dark card tone is the iOS surfaceCard value; a card slot equal to the
        // background left dark mode with no elevation cue (Android is deliberately shadowless).
        assertEquals(Color(0xFF3A352D), DefernoDarkColorScheme.surfaceContainerLow, "dark card = --paper-card")
        assertNotEquals(
            DefernoDarkColorScheme.background, DefernoDarkColorScheme.surfaceContainerLow,
            "dark cards must not render flat against the background",
        )
        assertEquals(Color(0xFFF2ECDC), DefernoLightColorScheme.surfaceContainerLow, "light card unchanged")
    }

    @Test
    fun monoPalettesAreGrayscale() {
        for (c in listOf(
            MonoLightColorScheme.primary, MonoLightColorScheme.secondary, MonoLightColorScheme.tertiary,
            MonoLightColorScheme.error, MonoDarkColorScheme.primary, MonoDarkColorScheme.error,
        )) {
            assertTrue(c.red == c.green && c.green == c.blue, "mono token should be grayscale: $c")
        }
        // mono collapses the accent onto ink, so primary and error coincide (no hue to tell apart).
        assertEquals(MonoLightColorScheme.primary, MonoLightColorScheme.error)
        assertNotEquals(DefernoLightColorScheme.primary, DefernoLightColorScheme.error)
    }

    @Test
    fun brandExtensionCarriesNonMaterialRoles() {
        assertEquals(Color(0xFF3F7A3F), DefernoLightBrand.success, "success = --green")
        assertEquals(Color(0xFFD2E3CC), DefernoLightBrand.successContainer, "successContainer = --green-soft")
        assertEquals(Color(0xFFA05F0E), DefernoLightBrand.amberDeep, "amberDeep = --amber-deep")
        assertEquals(Color(0xFF1F1A12), DefernoLightBrand.lineStrong, "lineStrong = --line")
        assertEquals(Color(0xFF9CC09C), DefernoDarkBrand.success)
        assertNotEquals(DefernoLightBrand.success, MonoLightBrand.success)
    }

    @Test
    fun bothPalettesEnumerated() {
        assertEquals(2, DefernoPalette.entries.size)
        assertTrue(DefernoPalette.Deferno in DefernoPalette.entries)
        assertTrue(DefernoPalette.Mono in DefernoPalette.entries)
    }
}
