package com.circuitstitch.deferno.shell

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.R
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.exp
import kotlin.math.sin

/**
 * The live "listening" spectrum: @integer/spectrum_bands bars whose heights track the real mic audio's
 * energy across ≈110 Hz–3.5 kHz (A2–A7; left = low, right = high), read from [levels] (0..1 per band,
 * published ~5×/s by the recorder's tap). Bars are mirrored about the vertical centre — a centred VU look.
 * Each animation frame eases the displayed heights toward the latest levels — a glide that decays to a thin
 * centred line on silence — painted in one [drawBehind] pass (house style). Only composed when motion is
 * allowed, so the frame loop is inert under reduced-motion.
 *
 * Every look-knob lives in `res/values` (`@integer/spectrum_bands`, `@dimen/spectrum_bar_*`) so the bars
 * tune in one place; [SPECTRUM_TAU_MS] is the only Kotlin-side knob (the smoothing time constant —
 * animation feel, not a dimension).
 */
@Composable
internal fun SpectrumBars(
    levels: StateFlow<FloatArray>,
    enabled: Boolean,
    onToggle: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val bands = integerResource(R.integer.spectrum_bands)
    val barHeight = dimensionResource(R.dimen.spectrum_bar_height)
    val barGap = dimensionResource(R.dimen.spectrum_bar_gap)
    val barMinWidth = dimensionResource(R.dimen.spectrum_bar_min_width)
    val barMinHeight = dimensionResource(R.dimen.spectrum_bar_min_height)
    val barCorner = dimensionResource(R.dimen.spectrum_bar_corner_radius)

    // One displayed array eased toward the latest target each frame — cheaper + smoother than re-keying a
    // per-bar Animatable on every new FloatArray instance. Snapshot state so the drawBehind redraws on tick.
    // Seeded from the current levels so a static @Preview (and the first frame) shows real bar heights
    // instead of easing up from flat; in-app the holder starts empty, so this changes nothing there.
    val displayed = remember(bands) {
        val seed = levels.value
        mutableStateListOf<Float>().apply { repeat(bands) { add(seed.getOrElse(it) { 0f }) } }
    }
    LaunchedEffect(levels, enabled) {
        var lastFrame = 0L
        while (true) {
            withInfiniteAnimationFrameMillis { frameMillis ->
                // Frame-rate-independent exponential smoothing: derive the per-frame lerp from the actual
                // frame Δt + a fixed time constant, so the glide feels identical at 60/90/120 Hz. First
                // frame (Δt 0) holds still. When feedback is off the target is zero, so the bars glide down
                // to the centred baseline and rest there (still a tap target to re-enable).
                val dt = if (lastFrame == 0L) 0L else frameMillis - lastFrame
                lastFrame = frameMillis
                val alpha = (1.0 - exp(-dt / SPECTRUM_TAU_MS)).toFloat()
                val target = levels.value
                for (i in displayed.indices) {
                    val t = if (enabled) target.getOrElse(i) { 0f } else 0f
                    displayed[i] += (t - displayed[i]) * alpha
                }
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(barHeight)
            .clickable(
                onClickLabel = if (enabled) "Turn off visual audio feedback" else "Turn on visual audio feedback",
                onClick = onToggle,
            )
            .drawBehind {
                val n = displayed.size
                if (n == 0) return@drawBehind
                val gap = barGap.toPx()
                val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(barMinWidth.toPx())
                val minH = barMinHeight.toPx().coerceAtMost(size.height) // a centred baseline dot when silent
                val radius = CornerRadius(barCorner.toPx(), barCorner.toPx())

                // Bars grow symmetrically from the vertical centre (mirrored up + down).
                for (i in 0 until n) {
                    val h = (displayed[i].coerceIn(0f, 1f) * size.height).coerceAtLeast(minH)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(i * (barW + gap), (size.height - h) / 2f),
                        size = Size(barW, h),
                        cornerRadius = radius,
                    )
                }
            },
    )
}

/**
 * Smoothing time constant (ms) for the VU-meter ease: each frame the displayed bars move toward the latest
 * mic levels by `1 - exp(-Δt / SPECTRUM_TAU_MS)`, so the glide feels identical regardless of display refresh
 * rate (60/90/120 Hz). Kept short so the bars track the 20 Hz mic feed near real-time; smaller = snappier,
 * larger = lazier (de-jitters more).
 */
private const val SPECTRUM_TAU_MS = 40.0

/**
 * Sample spectra for [SpectrumBarsPreview] — silence, a quiet take, a loud take, and full scale — so the
 * bar shape + height can be eyeballed in Android Studio without a live mic. Each value renders its own
 * preview frame; [SpectrumBars] seeds its displayed state from these, so the static render shows them at
 * height (not mid-ease).
 */
private class SpectrumLevelsProvider : PreviewParameterProvider<FloatArray> {
    // Preview fixture length only — the runtime band count is @integer/spectrum_bands. SpectrumBars seeds
    // `displayed` to the resource count and reads these via getOrElse, so any drift here is just cosmetic.
    private val bands = 36

    /** A voice-like spectrum: louder in the low (left) bands, tapering up the range, with a little ripple. */
    private fun voice(peak: Float) = FloatArray(bands) { i ->
        val tilt = peak * (1f - i / (bands - 1f))
        (tilt + 0.12f * sin(i * 1.3f)).coerceIn(0f, 1f)
    }

    override val values = sequenceOf(
        FloatArray(bands),        // silence → baseline dots
        voice(0.45f),             // quiet take
        voice(0.95f),             // loud take
        FloatArray(bands) { 1f }, // full scale
    )
}

@Preview(name = "Spectrum bars", widthDp = 360, showBackground = true, backgroundColor = 0xFF2A2620)
@Composable
private fun SpectrumBarsPreview(
    @PreviewParameter(SpectrumLevelsProvider::class) levels: FloatArray,
) {
    // The immersive dark paper the Brain dump surface forces (#2A2620 + amber), so the preview matches.
    DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            SpectrumBars(
                levels = MutableStateFlow(levels),
                enabled = true,
                onToggle = {},
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
