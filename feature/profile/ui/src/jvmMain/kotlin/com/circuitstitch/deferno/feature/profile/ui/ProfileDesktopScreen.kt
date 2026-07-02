package com.circuitstitch.deferno.feature.profile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.auth_session_expired_title
import com.circuitstitch.deferno.core.designsystem.resources.auth_unavailable_title
import com.circuitstitch.deferno.core.designsystem.resources.common_retry
import com.circuitstitch.deferno.core.designsystem.resources.common_sign_in_again
import com.circuitstitch.deferno.core.designsystem.resources.profile_session_expired_body
import com.circuitstitch.deferno.core.designsystem.resources.profile_unavailable_body
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileState
import org.jetbrains.compose.resources.stringResource

/**
 * The **Profile** Destination View, desktop edition (#84, ADR-0017): a thin renderer of
 * [ProfileComponent] (ADR-0003: holds no logic). It shows the **display identity** the Active Account
 * signs in as — the initials avatar, name, `@handle`, and personal Org — above the co-located
 * **Account controls** (the active Account and **Sign out**), reusing the shared commonMain atoms
 * ([IdentityCard], [AccountSection], [LoadingIdentity], [InlineNotice]).
 *
 * Desktop divergence from the Android screen (ADR-0007: not the phone layout stretched): the content is
 * held to a comfortable **reading width** and centred rather than spanning a wide window edge-to-edge,
 * as Plan's desktop screen does. **Sign out always works** even when the identity fetch is offline,
 * because it operates on the locally-vaulted Account, not the live `/auth/me` (ADR-0009).
 */
@Composable
fun ProfileDesktopScreen(component: ProfileComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val timeZone by component.timeZone.collectAsState()
    ProfileDesktopContent(
        account = component.account,
        state = state,
        timeZone = timeZone,
        onRetry = component::onRetry,
        onSignOut = component::onSignOut,
        modifier = modifier,
    )
}

/** Comfortable reading column width for the Profile on a wide desktop window. */
private val ProfileReadingWidth = 760.dp

/** Stateless body — easy to render in a preview / test with a fixed [ProfileState]. */
@Composable
internal fun ProfileDesktopContent(
    account: Account,
    state: ProfileState,
    timeZone: String?,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxSize().widthIn(max = ProfileReadingWidth)) {
            // The "Profile" title now lives in the shell's single top bar (Cand 1); this is just the body.
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
                        title = stringResource(Res.string.auth_session_expired_title),
                        body = stringResource(Res.string.profile_session_expired_body),
                        action = stringResource(Res.string.common_sign_in_again),
                        onAction = onRetry,
                    )

                    ProfileState.Unavailable -> InlineNotice(
                        title = stringResource(Res.string.auth_unavailable_title),
                        body = stringResource(Res.string.profile_unavailable_body),
                        action = stringResource(Res.string.common_retry),
                        onAction = onRetry,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // The Account controls are always available — sign-out works offline (ADR-0009).
                AccountSection(account = account, timeZone = timeZone, onSignOut = onSignOut)
            }
        }
    }
}
