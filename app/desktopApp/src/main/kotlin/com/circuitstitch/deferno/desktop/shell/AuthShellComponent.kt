package com.circuitstitch.deferno.desktop.shell

import com.arkivanov.decompose.ComponentContext

/**
 * The **Auth shell** (ADR-0013): the pre-[[Account]] surface, shown by [RootComponent] when there is
 * no [[Active Account]]. It owns its own navigation and holds no Org scope — login/MFA/account-picker
 * are **never** [[Destination]]s and never appear in the Main shell.
 *
 * For now this is a placeholder with a single [onSignInClicked] action (the real sign-in / MFA /
 * account-picker navigation arrives with #15); it simply asks the [AuthGate] to complete sign-in so
 * the shell boundary is demonstrable. It is intentionally Compose-free (the View renders it).
 */
interface AuthShellComponent {
    /** Complete the (stubbed) Auth flow → the [RootComponent] swaps to the Main shell. */
    fun onSignInClicked()
}

class DefaultAuthShellComponent(
    componentContext: ComponentContext,
    private val onSignIn: () -> Unit,
) : AuthShellComponent, ComponentContext by componentContext {

    override fun onSignInClicked() {
        onSignIn()
    }
}
