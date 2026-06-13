package com.circuitstitch.deferno.feature.signin

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.auth.SignInResult
import com.circuitstitch.deferno.core.data.auth.SignInService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 *
 * [onRetry] handles a browser sign-in the user started then abandoned: the external browser gives no
 * close event (macOS/desktop/Android, ADR-0026), so the in-flight leg would otherwise spin forever — it
 * cancels the stalled attempt (clearing the redirect inbox) and starts a fresh one.
 */
class DefaultSignInComponent(
    componentContext: ComponentContext,
    private val signInService: SignInService,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : SignInComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    private val _state = MutableStateFlow(SignInState())
    override val state: StateFlow<SignInState> = _state.asStateFlow()

    // The in-flight sign-in, so onRetry can cancel a stalled browser leg before relaunching.
    private var signInJob: Job? = null

    override fun onSignInClick() {
        if (!beginBusy()) return
        signInJob = launchAttempt { signInService.signInWithBrowser().toBrowserError() }
    }

    override fun onRetry() {
        // The external browser gives no close event (ADR-0026), so a started-then-abandoned sign-in
        // would spin forever. Cancel the stalled wait and start fresh — re-arming the inbox in the
        // browser leg cancels the prior redirect wait, so nothing leaks. isBusy is already true.
        signInJob?.cancel()
        _state.update { it.copy(isBusy = true, error = null) }
        signInJob = launchAttempt { signInService.signInWithBrowser().toBrowserError() }
    }

    override fun onUseTokenInstead() {
        _state.update { it.copy(showTokenEntry = true, error = null) }
    }

    override fun onTokenChange(token: String) {
        _state.update { it.copy(token = token, error = null) }
    }

    override fun onSubmit() {
        val token = _state.value.token.trim()
        if (token.isEmpty()) return
        if (!beginBusy()) return
        signInJob = launchAttempt { signInService.signIn(token).toPasteError() }
    }

    /**
     * Run [attempt] on [scope] and settle `isBusy = false` with whatever [SignInError] it mapped to
     * (`null` = success or browser-cancel; on success the shell swaps this surface away, ADR-0013).
     * The caller owns the in-flight guard (`beginBusy` for a fresh start; [onRetry] forces a restart).
     */
    private fun launchAttempt(attempt: suspend () -> SignInError?): Job = scope.launch {
        val error = attempt()
        _state.update { it.copy(isBusy = false, error = error) }
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
