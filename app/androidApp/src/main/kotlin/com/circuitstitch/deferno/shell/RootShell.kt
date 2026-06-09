package com.circuitstitch.deferno.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.feature.signin.ui.SignInScreen

/**
 * The root View (ADR-0013): renders whichever [[Shell]] the [RootComponent] has foreground — the Auth
 * shell (the paste-PAT sign-in surface, #15/ADR-0023) or the Main shell (the Destination graph). The
 * shell swaps reactively as the Active Account changes; this View is a pure renderer of that single
 * active child.
 */
@Composable
fun RootShell(component: RootComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    when (val child = stack.active.instance) {
        is RootComponent.Child.Auth -> SignInScreen(child.component.signIn, modifier)
        is RootComponent.Child.Main -> MainShell(child.component, modifier)
    }
}
