package com.circuitstitch.deferno.feature.signin

import kotlinx.coroutines.flow.StateFlow

/**
 * Observable state of the paste-PAT sign-in surface (#15, ADR-0023). The View renders this and holds
 * no business logic (ADR-0003/0007): the [token] the user is editing, whether a validation is in
 * flight, and the last [error] (cleared as soon as the user edits the token again).
 *
 * On a *successful* sign-in there is no terminal state here — establishing the Account flips the
 * Active Account, and the shell swaps the Auth surface for the Main shell out from under this
 * component (ADR-0013); it simply stops being rendered.
 */
data class SignInState(
    val token: String = "",
    val isValidating: Boolean = false,
    val error: SignInError? = null,
) {
    /** The button is live only with a non-blank token and no validation already running. */
    val canSubmit: Boolean get() = token.isNotBlank() && !isValidating
}

/**
 * Why a sign-in attempt failed, condensed for the View — distinct because the right response differs:
 * [InvalidToken] is a credential problem (fix the token), [Unavailable] is transient (just retry).
 */
enum class SignInError {
    /** `/auth/me` rejected the pasted token (a `401`): a typo or an expired/revoked PAT. */
    InvalidToken,

    /** A transient failure (offline, server/transport error). The token may be fine — retry. */
    Unavailable,
}

/**
 * The sign-in component (#15, ADR-0023): the user pastes a **personal access token**, the component
 * validates it via the [com.circuitstitch.deferno.core.data.auth.SignInService] (a one-off
 * `/auth/me`), and on success establishes the Account. It exposes the observable [state] the View
 * renders; navigation on success is **implicit** (the Active Account flips and the shell swaps), so
 * there is no success output to wire.
 */
interface SignInComponent {
    val state: StateFlow<SignInState>

    /** The user edited the token field. Clears any prior [SignInError]. */
    fun onTokenChange(token: String)

    /** The user submitted the token for validation (a no-op while blank or already validating). */
    fun onSubmit()
}
