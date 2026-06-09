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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The paste-PAT sign-in component (#15, ADR-0023). [onSubmit] hands the (trimmed) token to the
 * [SignInService]: while it validates, [SignInState.isValidating] is true; the outcome either
 * establishes the Account — at which point the shell swaps this surface away (ADR-0013), so there is
 * nothing more to do — or surfaces a [SignInError] for the View. The token is never logged (ADR-0009).
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

    override fun onTokenChange(token: String) {
        _state.update { it.copy(token = token, error = null) }
    }

    override fun onSubmit() {
        val token = _state.value.token.trim()
        if (token.isEmpty() || _state.value.isValidating) return
        scope.launch {
            _state.update { it.copy(isValidating = true, error = null) }
            val error = when (signInService.signIn(token)) {
                // Success flips the Active Account; the shell replaces this surface (ADR-0013). Clearing
                // the flag is harmless if it still renders for an instant before the swap.
                is SignInResult.Success -> null
                SignInResult.InvalidToken -> SignInError.InvalidToken
                SignInResult.Unavailable -> SignInError.Unavailable
            }
            _state.update { it.copy(isValidating = false, error = error) }
        }
    }
}
