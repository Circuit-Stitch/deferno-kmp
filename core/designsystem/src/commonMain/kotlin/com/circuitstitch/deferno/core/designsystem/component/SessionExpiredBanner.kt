package com.circuitstitch.deferno.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The shared "Session expired — sign in again" banner (#297): a full-width tinted strip the read
 * surfaces (Tasks / Plan / Search) render when an authenticated request `401`s, so an expired session
 * can't masquerade as a stale cache. The same affordance Profile already shows, lifted into the design
 * system so every surface (and both Compose hosts) renders one banner rather than reinventing it.
 *
 * A `liveRegion` so screen readers announce the expiry when it appears mid-session.
 */
@Composable
fun SessionExpiredBanner(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    message: String = "Session expired — sign in again to refresh.",
    action: String = "Sign in again",
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSignIn) { Text(action) }
        }
    }
}
