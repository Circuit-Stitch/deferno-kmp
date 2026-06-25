package com.circuitstitch.deferno.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.feature.signin.ui.SignInScreen
import com.circuitstitch.deferno.shell.RootComponent

/**
 * The root View, desktop edition (ADR-0013 / ADR-0017): renders whichever [[Shell]] the shared
 * [RootComponent] has foreground — the Auth shell (the paste-PAT sign-in surface, #15/ADR-0023) or the
 * Main shell (the Destination graph). The shell swaps reactively as the **Active Account** changes (the
 * shared root keys off `AccountManager.activeAccount`); this View is a pure renderer of that single
 * active child. The sign-in screen is the same Compose-Multiplatform View the Android host renders.
 *
 * [developerOptions] mirrors Android's `showDeveloperOptions = BuildConfig.DEBUG` (RootShell.kt): on an
 * unpackaged dev `run` it reveals the **"Use a token instead"** paste-PAT fallback (ADR-0023), so a
 * Sign Out is recoverable without the system-browser OAuth leg (ADR-0026, not yet wired against staging).
 */
@Composable
fun RootShell(component: RootComponent, modifier: Modifier = Modifier, developerOptions: Boolean = false) {
    val stack by component.stack.subscribeAsState()
    when (val child = stack.active.instance) {
        is RootComponent.Child.Auth ->
            SignInScreen(child.component.signIn, modifier, showDeveloperOptions = developerOptions)
        is RootComponent.Child.Main -> MainShell(child.component, modifier)
    }
}
