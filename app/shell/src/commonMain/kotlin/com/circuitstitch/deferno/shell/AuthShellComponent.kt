package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.circuitstitch.deferno.feature.signin.SignInComponent

/**
 * The **Auth shell** (ADR-0013): the pre-[[Account]] surface, shown by [RootComponent] when there is
 * no [[Active Account]]. It owns its own navigation and holds no Org scope — login/MFA/account-picker
 * are **never** [[Destination]]s and never appear in the Main shell.
 *
 * In v1 its sole child is the paste-PAT [signIn] surface (#15, ADR-0023): the user pastes a personal
 * access token, it is validated against `/auth/me` and stored, and the Active Account flips — at which
 * point [RootComponent] swaps this shell for the Main shell. (An MFA challenge and an account picker
 * land here later.) It is intentionally Compose-free (the View renders it).
 */
interface AuthShellComponent {
    /** The sign-in surface this shell hosts — v1's only Auth-shell child. */
    val signIn: SignInComponent
}

class DefaultAuthShellComponent(
    componentContext: ComponentContext,
    signIn: (ComponentContext) -> SignInComponent,
) : AuthShellComponent, ComponentContext by componentContext {

    override val signIn: SignInComponent = signIn(childContext(key = "SignIn"))
}
