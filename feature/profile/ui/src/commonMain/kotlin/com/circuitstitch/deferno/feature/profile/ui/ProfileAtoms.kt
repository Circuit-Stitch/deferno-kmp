package com.circuitstitch.deferno.feature.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.User

/*
 * Shared, stateless Profile atoms (#84). Extracted out of the Android `ProfileScreen` so the
 * Android (androidMain) and desktop (jvmMain) Profile Views render the *same* identity card and
 * Account controls — one definition, no per-platform copies (ADR-0007). Everything here is
 * Compose-Multiplatform-common + `core/designsystem` tokens; there is no iOS source set (the iOS
 * Profile is SwiftUI, ADR-0003/0004). `internal` so the whole module — both screens — can see them.
 */

/** Minimum height for a tappable row/control — design-principles.md "≥44–48dp" touch targets. */
internal val MinTouchTarget = 48.dp

/**
 * The signed-in **identity card** (`ProfileState.SignedIn`): the initials avatar, display name,
 * `@handle`, and the personal Org. Renders only what `GET /auth/me` carries today — no email, no
 * avatar URL, a single personal Org (ADR-0015's "one knowable Org").
 */
@Composable
internal fun IdentityCard(user: User) {
    val name = user.displayName.ifBlank { user.username }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(name)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = plexMono(),
                    color = MaterialTheme.defernoColors.inkMuted,
                )
            }
        }

        // The personal Org — the lone Org a v1 user can know (ADR-0015: no org-listing API yet).
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Organization",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    label = user.orgSlug.ifBlank { "Personal" },
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (user.isAdmin) {
                    Chip(
                        label = "Admin",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * The co-located **Account controls** — the Active Account, the on-device credential line, and
 * **Sign out** (behind a destructive-action confirm dialog). These sit *alongside* the identity,
 * not within it (CONTEXT.md), and are always available: sign-out operates on the locally-vaulted
 * Account, not the live `/auth/me`, so it works even when the identity fetch is offline (ADR-0009).
 * Confirming calls [onSignOut] — the View raises `Output.SignOutRequested`; the shell does the wipe.
 */
@Composable
internal fun AccountSection(account: Account, timeZone: String?, onSignOut: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Account",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        LabeledRow(label = "Active account", value = account.label)
        // Time zone moved here from Settings → Account (#72); offline-first, so it always renders.
        LabeledRow(label = "Time zone", value = timeZone ?: "Device default")
        LabeledRow(label = "Credential", value = "Personal access token")
        Text(
            text = "Stored only on this device.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        Button(
            onClick = { showConfirm = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        ) { Text("Sign out") }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Sign out of ${account.label}?") },
            text = {
                Text(
                    "This removes this account and its data from this device. " +
                        "You’ll need to sign in again to use it here.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onSignOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/** A label/value stack — one read-only field of the Account controls. */
@Composable
internal fun LabeledRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A circular avatar showing the User's [initialsOf] initials over the brand container colour. */
@Composable
internal fun InitialsAvatar(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            // The visible name sits right beside the avatar, so the initials are decorative for TalkBack.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** A small rounded label pill (the Org / Admin chips). */
@Composable
internal fun Chip(label: String, container: Color, content: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The calm working strip shown while the identity fetch is in flight (`ProfileState.Loading`). */
@Composable
internal fun LoadingIdentity() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(
            text = "Loading your profile…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
    }
}

/**
 * An inline notice with a single retry action — the body for the `ReauthRequired` and `Unavailable`
 * states. The two differ only in copy + button label; both call [onAction] (the component's retry).
 */
@Composable
internal fun InlineNotice(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        OutlinedButton(
            onClick = onAction,
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) { Text(action) }
    }
}

/** First letters of the first and last whitespace-separated words, uppercased — the avatar fallback. */
internal fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.first().take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}
