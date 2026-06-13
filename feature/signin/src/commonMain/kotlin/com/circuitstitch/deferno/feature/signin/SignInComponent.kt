package com.circuitstitch.deferno.feature.signin

import kotlinx.coroutines.flow.StateFlow

/**
 * Observable state of the sign-in surface (#15, ADR-0012/0026). The View renders this and holds no
 * business logic (ADR-0003/0007). The primary action is **Sign in** — the system-browser OAuth flow,
 * which ships no in-app credential field. A developer paste fallback (ADR-0023) is revealed by
 * [showTokenEntry] once the user taps the dev affordance; until then only [isBusy] / [error] matter.
 *
 * On a *successful* sign-in there is no terminal state here — establishing the Account flips the Active
 * Account, and the shell swaps the Auth surface for Main out from under this component (ADR-0013); it
 * simply stops being rendered. A cancelled browser flow returns silently to idle (no error).
 */
data class SignInState(
    val isBusy: Boolean = false,
    val error: SignInError? = null,
    val showTokenEntry: Boolean = false,
    val token: String = "",
) {
    /** The browser **Sign in** button is live whenever a flow isn't already running. */
    val canStartBrowser: Boolean get() = !isBusy

    /**
     * A browser sign-in is in flight, so the "Need to try again?" escape hatch is offered. The external
     * browser (macOS/desktop/Android) gives the app no tab-close event (ADR-0026), so an abandoned
     * sign-in can't auto-cancel — this lets the user restart. Not the paste path (no browser to abandon).
     */
    val canRetryBrowser: Boolean get() = isBusy && !showTokenEntry

    /** The dev paste button is live only with a non-blank token and no flow already running. */
    val canSubmitToken: Boolean get() = token.isNotBlank() && !isBusy
}

/**
 * Why a sign-in attempt failed, condensed for the View. [Unavailable] is transient (retry); [InvalidToken]
 * is paste-path-only (the pasted PAT was rejected — fix it and retry). A user-cancelled browser flow is
 * **not** an error — it returns the surface to idle without a message.
 */
enum class SignInError {
    /** A pasted token was rejected by `/auth/me` (a `401`): a typo or an expired/revoked PAT. */
    InvalidToken,

    /** A transient failure (offline, server/transport, a failed browser leg). Retry. */
    Unavailable,
}

/**
 * The sign-in component (#15, ADR-0012/0026): drives the system-browser OAuth flow via the
 * [com.circuitstitch.deferno.core.data.auth.SignInService], and — behind a dev affordance — the paste
 * fallback. Exposes the observable [state] the View renders; navigation on success is **implicit** (the
 * Active Account flips and the shell swaps), so there is no success output to wire.
 */
interface SignInComponent {
    val state: StateFlow<SignInState>

    /** The user tapped **Sign in** — start the system-browser OAuth + PKCE flow. */
    fun onSignInClick()

    /**
     * The user tapped "Need to try again?" while a browser sign-in is in flight. The external browser
     * gives no close event (ADR-0026), so a started-then-abandoned flow would spin forever; this cancels
     * the stalled attempt and starts a fresh one. Only meaningful (and offered) while [SignInState.canRetryBrowser].
     */
    fun onRetry()

    /** The user tapped the developer "Use a token instead" affordance — reveal the paste field. */
    fun onUseTokenInstead()

    /** The user edited the token field. Clears any prior [SignInError]. */
    fun onTokenChange(token: String)

    /** The user submitted a pasted token for validation (a no-op while blank or already busy). */
    fun onSubmit()
}
