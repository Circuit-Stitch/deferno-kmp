package com.circuitstitch.deferno.feature.signin

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.circuitstitch.deferno.core.data.auth.SignInResult
import com.circuitstitch.deferno.core.data.auth.SignInService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The sign-in component (#15, ADR-0012/0026). [onSignInClick] runs the system-browser OAuth + PKCE flow
 * via the [SignInService]; [onSubmit] is the dev paste fallback. While either is in flight
 * [SignInState.isBusy] is true; the outcome either establishes the Account — at which point the shell
 * swaps this surface away (ADR-0013), so there is nothing more to do — surfaces a [SignInError] for the
 * View, or (browser cancel) returns silently to idle. No token is ever logged (ADR-0009).
 */
class DefaultSignInComponent(
    componentContext: ComponentContext,
    private val signInService: SignInService,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : SignInComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = CoroutineScope(coroutineContext + SupervisorJob())
        .also { s -> lifecycle.doOnDestroy { s.cancel() } }

    private val _state = MutableStateFlow(SignInState())
    override val state: StateFlow<SignInState> = _state.asStateFlow()

    override fun onSignInClick() = launchSignIn { signInService.signInWithBrowser().toBrowserError() }

    override fun onUseTokenInstead() {
        _state.update { it.copy(showTokenEntry = true, error = null) }
    }

    override fun onTokenChange(token: String) {
        _state.update { it.copy(token = token, error = null) }
    }

    override fun onSubmit() {
        val token = _state.value.token.trim()
        if (token.isEmpty()) return
        launchSignIn { signInService.signIn(token).toPasteError() }
    }

    /**
     * The shared launch path for both sign-in surfaces: flip the in-flight flag (so a second tap is
     * dropped), run [attempt] on [scope], then settle `isBusy = false` with whatever [SignInError] it
     * mapped to (`null` = success or browser-cancel; on success the shell swaps this surface away,
     * ADR-0013). A no-op if a flow is already running.
     */
    private fun launchSignIn(attempt: suspend () -> SignInError?) {
        if (!beginBusy()) return
        scope.launch {
            val error = attempt()
            _state.update { it.copy(isBusy = false, error = error) }
        }
    }

    // InvalidToken can't arise on the browser path (the token is freshly minted) — fold it into the
    // transient bucket defensively. Success / Cancelled (the user backed out) settle to idle, no error.
    private fun SignInResult.toBrowserError(): SignInError? = when (this) {
        is SignInResult.Success, SignInResult.Cancelled -> null
        SignInResult.InvalidToken, SignInResult.Unavailable -> SignInError.Unavailable
    }

    private fun SignInResult.toPasteError(): SignInError? = when (this) {
        is SignInResult.Success, SignInResult.Cancelled -> null
        SignInResult.InvalidToken -> SignInError.InvalidToken
        SignInResult.Unavailable -> SignInError.Unavailable
    }

    /**
     * Flips the in-flight flag SYNCHRONOUSLY (compare-and-set) before launching, so two taps in the same
     * frame — both seeing `isBusy == false` — can't each fire a sign-in. Returns false if a flow is
     * already running (the caller should bail).
     */
    private fun beginBusy(): Boolean {
        val previous = _state.getAndUpdate { if (it.isBusy) it else it.copy(isBusy = true, error = null) }
        return !previous.isBusy
    }
}
