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
 * The paste-PAT sign-in screen (#15, ADR-0023): the Auth-shell View that renders the shared
 * [SignInComponent]. The token field is masked with a reveal toggle (ADR-0009: don't expose secrets on
 * screen), and validation state / errors are surfaced inline. On success the component flips the Active
 * Account and the shell swaps this surface for the Main shell — there is nothing for the View to do.
 *
 * Compose-Multiplatform commonMain: the same screen renders on Android and desktop.
 */
@Composable
fun SignInScreen(component: SignInComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    SignInContent(
        state = state,
        onTokenChange = component::onTokenChange,
        onSubmit = component::onSubmit,
        modifier = modifier,
    )
}

/** Stateless body — rendered directly by the render/screenshot tests with fixed inputs. */
@Composable
internal fun SignInContent(
    state: SignInState,
    onTokenChange: (String) -> Unit,
    onSubmit: () -> Unit,
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
                    text = "Sign in with a personal access token",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )

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
                    enabled = !state.isValidating,
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
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    Text(text = if (state.isValidating) "Signing in…" else "Sign in")
                }

                Text(
                    text = "Create a token in Deferno on the web: Settings → Tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

private fun errorMessage(error: SignInError): String = when (error) {
    SignInError.InvalidToken -> "That token isn’t valid. Check it and try again."
    SignInError.Unavailable -> "Couldn’t reach Deferno. Check your connection and try again."
}
