package com.circuitstitch.deferno.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.BuildConfig
import com.circuitstitch.deferno.feature.signin.ui.SignInScreen

/**
 * The root View (ADR-0013): renders whichever [[Shell]] the [RootComponent] has foreground — the Auth
 * shell (the browser-OAuth sign-in surface, #15/ADR-0026) or the Main shell (the Destination graph). The
 * shell swaps reactively as the Active Account changes; this View is a pure renderer of that single
 * active child. The paste-PAT fallback (ADR-0023) is offered only in debug builds.
 */
@Composable
fun RootShell(component: RootComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    when (val child = stack.active.instance) {
        is RootComponent.Child.Auth ->
            // onCancel is non-null only when re-entered to add an account (#NN) — renders a Cancel-back.
            SignInScreen(child.component.signIn, modifier, showDeveloperOptions = BuildConfig.DEBUG, onCancel = child.component.onCancel)
        is RootComponent.Child.Main -> MainShell(child.component, modifier)
    }
}
