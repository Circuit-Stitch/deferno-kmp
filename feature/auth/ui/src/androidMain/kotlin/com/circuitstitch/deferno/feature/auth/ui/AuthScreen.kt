package com.circuitstitch.deferno.feature.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.auth_session_expired_body
import com.circuitstitch.deferno.core.designsystem.resources.auth_session_expired_title
import com.circuitstitch.deferno.core.designsystem.resources.auth_signed_in_as
import com.circuitstitch.deferno.core.designsystem.resources.auth_unavailable_body
import com.circuitstitch.deferno.core.designsystem.resources.auth_unavailable_title
import com.circuitstitch.deferno.core.designsystem.resources.common_retry
import com.circuitstitch.deferno.core.designsystem.resources.common_sign_in_again
import com.circuitstitch.deferno.core.designsystem.resources.common_signing_in
import com.circuitstitch.deferno.core.designsystem.resources.common_username_handle
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.feature.auth.AuthComponent
import com.circuitstitch.deferno.feature.auth.AuthState
import org.jetbrains.compose.resources.stringResource

/**
 * The #20 tracer's minimal identity screen — the visible end of the `/auth/me` vertical slice. A thin
 * renderer of [AuthComponent] (ADR-0003/0007: holds no logic): it observes the component's [AuthState]
 * and renders the signed-in [User]'s display name + username, a re-auth prompt on a 401, a retry on a
 * transient failure, or a spinner while the fetch is in flight. The Android-native View; a desktop/iOS
 * sign-in surface is a separate follow-up (ADR-0007), so there is no shared atom yet.
 */
@Composable
fun AuthScreen(component: AuthComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    AuthContent(state = state, onRetry = component::onRetry, modifier = modifier)
}

/** Stateless body — rendered directly by screenshot/UI tests with a fixed [AuthState]. */
@Composable
internal fun AuthContent(state: AuthState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            AuthState.Loading -> {
                CircularProgressIndicator()
                Text(
                    text = stringResource(Res.string.common_signing_in),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            is AuthState.SignedIn -> SignedIn(state.user)

            AuthState.ReauthRequired -> Retryable(
                title = stringResource(Res.string.auth_session_expired_title),
                body = stringResource(Res.string.auth_session_expired_body),
                action = stringResource(Res.string.common_sign_in_again),
                onAction = onRetry,
            )

            AuthState.Unavailable -> Retryable(
                title = stringResource(Res.string.auth_unavailable_title),
                body = stringResource(Res.string.auth_unavailable_body),
                action = stringResource(Res.string.common_retry),
                onAction = onRetry,
            )
        }
    }
}

@Composable
private fun SignedIn(user: User) {
    Text(
        text = stringResource(Res.string.auth_signed_in_as),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = user.displayName,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
    Text(
        text = stringResource(Res.string.common_username_handle, user.username),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun Retryable(title: String, body: String, action: String, onAction: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
    Button(
        onClick = onAction,
        modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp),
    ) { Text(action) }
}
