package com.circuitstitch.deferno.feature.auth

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Observable state of the authenticated-identity surface (#20 tracer): the result of resolving the
 * Active Account's `/auth/me`. The View renders this and holds no business logic (ADR-0003/0007).
 */
sealed interface AuthState {
    /** The fetch is in flight (the initial state, and while a retry runs). */
    data object Loading : AuthState

    /** The Active Account's identity resolved — the [user] the screen renders. */
    data class SignedIn(val user: User) : AuthState

    /**
     * The Active Account's credential is invalid/expired (a `401`). The repository has already raised
     * a re-auth request scoped to this Account (ADR-0002); the screen prompts a sign-in retry.
     */
    data object ReauthRequired : AuthState

    /** A transient failure (offline, server error). A retry is the right response — the Account stays signed in. */
    data object Unavailable : AuthState
}

/**
 * The authenticated-identity component (#20): on creation it fetches the Active Account's `/auth/me`
 * via [AuthRepository] and exposes the outcome as observable [state]. A one-shot fetch (not a local
 * `Flow`) because the tracer has no identity cache yet — the identity is resolved live per scene.
 * [onRetry] re-runs the fetch (after a transient failure, or to re-check after a re-auth prompt).
 *
 * The re-auth *routing* lives in the repository (it knows the Active Account, ADR-0002); this
 * component only reflects [MeResult.Unauthorized] as [AuthState.ReauthRequired] for the View.
 */
interface AuthComponent {
    val state: StateFlow<AuthState>

    /** Re-fetches `/auth/me` — e.g. the user tapped "try again" / "sign in again". */
    fun onRetry()
}

class DefaultAuthComponent(
    componentContext: ComponentContext,
    private val authRepository: AuthRepository,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : AuthComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        load()
    }

    override fun onRetry() {
        load()
    }

    private fun load() {
        scope.launch {
            _state.value = AuthState.Loading
            _state.value = when (val result = authRepository.loadMe()) {
                is MeResult.Authenticated -> AuthState.SignedIn(result.user)
                MeResult.Unauthorized -> AuthState.ReauthRequired
                MeResult.Unavailable -> AuthState.Unavailable
            }
        }
    }
}
