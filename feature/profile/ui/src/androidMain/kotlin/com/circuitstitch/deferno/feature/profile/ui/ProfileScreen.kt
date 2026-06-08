package com.circuitstitch.deferno.feature.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.designsystem.theme.plexMono
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileState

/** Minimum height for a tappable row/control — design-principles.md "≥44–48dp" touch targets. */
private val MinTouchTarget = 48.dp

/**
 * The **Profile** Destination View (#70, ADR-0013): a thin renderer of [ProfileComponent] (ADR-0003:
 * holds no logic). It shows the **display identity of the [[User]]** the Active Account signs in as —
 * an (initials) avatar, name, `@handle`, and the personal Org — above the co-located **Account
 * controls** (the active Account and **Sign out**).
 *
 * It renders only what `GET /auth/me` actually carries today (no email, no avatar URL, and a single
 * personal Org — ADR-0015's "one knowable Org"); the wider identity card lands when the backend grows
 * those fields. **Sign out always works**, even when the identity fetch is offline, because it operates
 * on the locally-vaulted Account, not the live `/auth/me` (ADR-0009).
 */
@Composable
fun ProfileScreen(component: ProfileComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    ProfileContent(
        account = component.account,
        state = state,
        onRetry = component::onRetry,
        onSignOut = component::onSignOut,
        modifier = modifier,
    )
}

/** Stateless body — rendered directly by screenshot/UI tests with a fixed [ProfileState]. */
@Composable
internal fun ProfileContent(
    account: Account,
    state: ProfileState,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ProfileHeader()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The identity card depends on the live /auth/me fetch.
            when (state) {
                ProfileState.Loading -> LoadingIdentity()
                is ProfileState.SignedIn -> IdentityCard(state.user)
                ProfileState.ReauthRequired -> InlineNotice(
                    title = "Session expired",
                    body = "Your sign-in for this account has ended. Sign in again to refresh your details.",
                    action = "Sign in again",
                    onAction = onRetry,
                )

                ProfileState.Unavailable -> InlineNotice(
                    title = "Can’t reach Deferno",
                    body = "Your profile details couldn’t load. Check your connection and try again.",
                    action = "Retry",
                    onAction = onRetry,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // The Account controls are always available — sign-out works offline (ADR-0009).
            AccountSection(account = account, onSignOut = onSignOut)
        }
    }
}

@Composable
private fun ProfileHeader() {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Profile",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

@Composable
private fun IdentityCard(user: User) {
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

@Composable
private fun AccountSection(account: Account, onSignOut: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Account",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        LabeledRow(label = "Active account", value = account.label)
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

@Composable
private fun LabeledRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InitialsAvatar(name: String, modifier: Modifier = Modifier) {
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

@Composable
private fun Chip(label: String, container: Color, content: Color) {
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

@Composable
private fun LoadingIdentity() {
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

@Composable
private fun InlineNotice(title: String, body: String, action: String, onAction: () -> Unit) {
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
private fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.first().take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

// --- @Preview ---

private fun previewUser(): User = User(
    id = UserId("u-1"),
    username = "ada",
    displayName = "Ada Lovelace",
    role = "admin",
    personalOrgId = OrgId("org-1"),
    orgSlug = "u-ada",
    isAdmin = true,
    consoleUrl = "https://console.example.com",
)

private fun previewAccount(): Account = Account(id = AccountId("a-1"), label = "Personal")

@Preview
@Composable
private fun ProfileContentSignedInPreview() {
    DefernoTheme {
        ProfileContent(
            account = previewAccount(),
            state = ProfileState.SignedIn(previewUser()),
            onRetry = {},
            onSignOut = {},
        )
    }
}

@Preview
@Composable
private fun ProfileContentLoadingPreview() {
    DefernoTheme {
        ProfileContent(
            account = previewAccount(),
            state = ProfileState.Loading,
            onRetry = {},
            onSignOut = {},
        )
    }
}
