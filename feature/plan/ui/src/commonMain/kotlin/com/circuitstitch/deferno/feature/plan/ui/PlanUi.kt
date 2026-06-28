package com.circuitstitch.deferno.feature.plan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors

// Shared building blocks for the Plan View (#27): thin, stateless Composables (loading + empty
// states). Kept in commonMain (Android + desktop) so a future desktop Plan View can reuse them; the
// Android-native screen that arranges them lives in androidMain (PlanScreen.kt).

/** Minimum height for a tappable row/control — design-principles.md "≥44–48dp" touch targets. */
internal val MinTouchTarget = 48.dp

/**
 * A calm, **static** "working…" strip — the reduced-motion alternative to an animated progress bar
 * (design-principles.md: honor reduced-motion). Plain text, announced politely to screen readers.
 */
@Composable
internal fun LoadingStrip(label: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Gentle, non-judgmental empty plan (design-principles.md: lapses are normal; no pressure). */
@Composable
internal fun EmptyPlan(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Your plan is clear", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Nothing scheduled for today. Add something when you're ready — no pressure.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
    }
}
