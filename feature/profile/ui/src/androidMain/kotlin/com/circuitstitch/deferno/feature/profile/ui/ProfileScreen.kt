package com.circuitstitch.deferno.feature.profile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileState

/**
 * The **Profile** Destination View (#70, ADR-0013): a thin renderer of [ProfileComponent] (ADR-0003:
 * holds no logic). It shows the **display identity of the [[User]]** the Active Account signs in as —
 * an (initials) avatar, name, `@handle`, and the personal Org — above the co-located **Account
 * controls** (the active Account and **Sign out**). The identity card and Account controls are the
 * shared atoms in [IdentityCard] / [AccountSection] (commonMain, #84), also rendered on desktop.
 *
 * It renders only what `GET /auth/me` actually carries today (no email, no avatar URL, and a single
 * personal Org — ADR-0015's "one knowable Org"); the wider identity card lands when the backend grows
 * those fields. **Sign out always works**, even when the identity fetch is offline, because it operates
 * on the locally-vaulted Account, not the live `/auth/me` (ADR-0009).
 */
@Composable
fun ProfileScreen(component: ProfileComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val timeZone by component.timeZone.collectAsState()
    ProfileContent(
        account = component.account,
        state = state,
        timeZone = timeZone,
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
    timeZone: String?,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The "Profile" title now lives in the shell's single top bar (Cand 1); this pane is just the body.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Edge-to-edge (ADR-0035 #2): pad the last control (Sign out) clear of the system nav bar.
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
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
        AccountSection(account = account, timeZone = timeZone, onSignOut = onSignOut)
    }
}

