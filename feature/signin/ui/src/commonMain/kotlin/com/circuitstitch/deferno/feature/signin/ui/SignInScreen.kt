package com.circuitstitch.deferno.feature.signin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.feature.signin.SignInComponent
import com.circuitstitch.deferno.feature.signin.SignInError
import com.circuitstitch.deferno.feature.signin.SignInState

/**
 * The sign-in screen (#15, ADR-0012/0026): the Auth-shell View that renders the shared [SignInComponent].
 * The primary action is **Sign in** — the system-browser OAuth flow, so there is **no in-app credential
 * field** by default (password + MFA + SSO happen in the browser). When [showDeveloperOptions] is set
 * (debug builds), a "Use a token instead" affordance reveals the paste-PAT fallback (ADR-0023): a masked
 * field with a reveal toggle (ADR-0009). On success the component flips the Active Account and the shell
 * swaps this surface for Main — there is nothing for the View to do.
 *
 * Compose-Multiplatform commonMain: the same screen renders on Android and desktop.
 */
@Composable
fun SignInScreen(
    component: SignInComponent,
    modifier: Modifier = Modifier,
    showDeveloperOptions: Boolean = false,
    // Non-null only when the Auth shell was re-entered to *add* an account while signed in (#NN): renders a
    // Cancel-back to the Main shell. Null on a first sign-in / after sign-out (nothing to return to).
    onCancel: (() -> Unit)? = null,
) {
    val state by component.state.collectAsState()
    SignInContent(
        state = state,
        onSignInClick = component::onSignInClick,
        onRetry = component::onRetry,
        onUseTokenInstead = component::onUseTokenInstead,
        onTokenChange = component::onTokenChange,
        onSubmit = component::onSubmit,
        showDeveloperOptions = showDeveloperOptions,
        onCancel = onCancel,
        modifier = modifier,
    )
}

/** Stateless body — rendered directly by the render/screenshot tests with fixed inputs. */
@Composable
internal fun SignInContent(
    state: SignInState,
    onSignInClick: () -> Unit,
    onRetry: () -> Unit,
    onUseTokenInstead: () -> Unit,
    onTokenChange: (String) -> Unit,
    onSubmit: () -> Unit,
    showDeveloperOptions: Boolean,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "Deferno", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = if (onCancel == null) "Sign in to your account" else "Add another account",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Button(
                    onClick = onSignInClick,
                    enabled = state.canStartBrowser,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                ) {
                    Text(text = if (state.isBusy) "Signing in…" else "Sign in")
                }
                Text(
                    text = "A secure browser window opens to finish signing in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )

                // The external browser gives no close event (ADR-0026), so a started-then-abandoned
                // sign-in can't auto-cancel — offer an explicit restart while the leg is in flight.
                if (state.canRetryBrowser) {
                    Text(
                        text = "Need to try again?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
                        Text(text = "Sign in")
                    }
                }

                // Browser-path error (the paste path shows its error inline on the field instead).
                val error = state.error
                if (error != null && !state.showTokenEntry) {
                    Text(
                        text = errorMessage(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                if (showDeveloperOptions) {
                    if (state.showTokenEntry) {
                        TokenEntry(state = state, onTokenChange = onTokenChange, onSubmit = onSubmit)
                    } else {
                        TextButton(
                            onClick = onUseTokenInstead,
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text(text = "Use a token instead") }
                    }
                }

                // Add-account re-entry (#NN): a Cancel-back to the Main shell. Absent on a first sign-in.
                if (onCancel != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.padding(top = 16.dp)) {
                        Text(text = "Cancel")
                    }
                }
            }
        }
    }
}

/** The developer paste-PAT fallback (ADR-0023): a masked token field + reveal toggle + submit. */
@Composable
private fun TokenEntry(state: SignInState, onTokenChange: (String) -> Unit, onSubmit: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    val error = state.error
    val supporting: (@Composable () -> Unit)? =
        if (error != null) {
            { Text(text = errorMessage(error)) }
        } else {
            null
        }
    OutlinedTextField(
        value = state.token,
        onValueChange = onTokenChange,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        label = { Text(text = "Personal access token") },
        singleLine = true,
        enabled = !state.isBusy,
        isError = state.error != null,
        // A credential field: mask it, and keep the opaque PAT out of the IME's autocorrect /
        // learned-words / suggestion store (ADR-0009 — don't leak secrets).
        visualTransformation =
            if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        trailingIcon = {
            TextButton(onClick = { revealed = !revealed }) {
                Text(text = if (revealed) "Hide" else "Show")
            }
        },
        supportingText = supporting,
    )

    Button(
        onClick = onSubmit,
        enabled = state.canSubmitToken,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Text(text = if (state.isBusy) "Signing in…" else "Sign in with token")
    }

    Text(
        text = "Create a token in Deferno on the web: Settings → Tokens.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )
}

private fun errorMessage(error: SignInError): String = when (error) {
    SignInError.InvalidToken -> "That token isn’t valid. Check it and try again."
    SignInError.Unavailable -> "Couldn’t reach Deferno. Check your connection and try again."
}
