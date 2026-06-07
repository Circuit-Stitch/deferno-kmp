package com.circuitstitch.deferno.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme

/**
 * The root View (ADR-0013): renders whichever [[Shell]] the [RootComponent] has foreground — the Auth
 * shell (pre-[[Account]]) or the Main shell (the Destination graph). The shell swaps reactively as the
 * Active Account changes; this View is a pure renderer of that single active child.
 */
@Composable
fun RootShell(component: RootComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    when (val child = stack.active.instance) {
        is RootComponent.Child.Auth -> AuthShellScreen(child.component, modifier)
        is RootComponent.Child.Main -> MainShell(child.component, modifier)
    }
}

/**
 * The Auth shell View — a pre-Account placeholder until #15 (sign-in / MFA / account-picker). It is
 * deliberately not a [[Destination]] and carries no nav suite (ADR-0013). "Continue" completes the
 * stubbed Auth flow, crossing into the Main shell.
 */
@Composable
private fun AuthShellScreen(component: AuthShellComponent, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "Deferno", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Sign-in arrives with #15. Continue to enter the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = component::onSignInClicked,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(text = "Continue")
            }
        }
    }
}

@Preview
@Composable
private fun AuthShellScreenPreview() {
    DefernoTheme {
        AuthShellScreen(component = object : AuthShellComponent {
            override fun onSignInClicked() = Unit
        })
    }
}
