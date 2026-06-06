package com.circuitstitch.deferno.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.ibm_plex_mono_medium
import com.circuitstitch.deferno.core.designsystem.resources.ibm_plex_mono_regular
import com.circuitstitch.deferno.core.designsystem.resources.ibm_plex_sans_bold
import com.circuitstitch.deferno.core.designsystem.resources.ibm_plex_sans_italic
import com.circuitstitch.deferno.core.designsystem.resources.ibm_plex_sans_medium
import com.circuitstitch.deferno.core.designsystem.resources.ibm_plex_sans_regular
import com.circuitstitch.deferno.core.designsystem.resources.ibm_plex_sans_semibold
import org.jetbrains.compose.resources.Font

/**
 * IBM Plex Sans — the Deferno UI type family (titles, body, labels). Built from the static weights
 * bundled as Compose Resources. `@Composable` because Compose Resources resolves fonts in
 * composition; [DefernoTheme] builds it once and hands it to `MaterialTheme`.
 */
@Composable
internal fun plexSans(): FontFamily = FontFamily(
    Font(Res.font.ibm_plex_sans_regular, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.ibm_plex_sans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(Res.font.ibm_plex_sans_medium, FontWeight.Medium, FontStyle.Normal),
    Font(Res.font.ibm_plex_sans_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(Res.font.ibm_plex_sans_bold, FontWeight.Bold, FontStyle.Normal),
)

/**
 * IBM Plex Mono — for tabular/monospace contexts: a Task's human reference (`{org_slug}-{sequence}`),
 * numerics, and code. Public so feature Views can apply it to those spans via `MaterialTheme`-adjacent
 * styling.
 */
@Composable
fun plexMono(): FontFamily = FontFamily(
    Font(Res.font.ibm_plex_mono_regular, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.ibm_plex_mono_medium, FontWeight.Medium, FontStyle.Normal),
)

private fun TextStyle.on(family: FontFamily, weight: FontWeight? = null): TextStyle =
    copy(fontFamily = family, fontWeight = weight ?: fontWeight)

/**
 * The Material 3 type scale set in IBM Plex Sans. Sizes/line-heights keep the M3 defaults (a known,
 * accessible scale that respects dynamic type); only the family and emphasis weights are Deferno's —
 * SemiBold headlines/titles and Medium labels, matching the web pattern library's heavier headings.
 */
@Composable
internal fun defernoTypography(): Typography {
    val sans = plexSans()
    return Typography().run {
        copy(
            displayLarge = displayLarge.on(sans),
            displayMedium = displayMedium.on(sans),
            displaySmall = displaySmall.on(sans),
            headlineLarge = headlineLarge.on(sans, FontWeight.SemiBold),
            headlineMedium = headlineMedium.on(sans, FontWeight.SemiBold),
            headlineSmall = headlineSmall.on(sans, FontWeight.SemiBold),
            titleLarge = titleLarge.on(sans, FontWeight.SemiBold),
            titleMedium = titleMedium.on(sans, FontWeight.Medium),
            titleSmall = titleSmall.on(sans, FontWeight.Medium),
            bodyLarge = bodyLarge.on(sans),
            bodyMedium = bodyMedium.on(sans),
            bodySmall = bodySmall.on(sans),
            labelLarge = labelLarge.on(sans, FontWeight.Medium),
            labelMedium = labelMedium.on(sans, FontWeight.Medium),
            labelSmall = labelSmall.on(sans, FontWeight.Medium),
        )
    }
}
