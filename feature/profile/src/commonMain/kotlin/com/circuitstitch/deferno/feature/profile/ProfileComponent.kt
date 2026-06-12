package com.circuitstitch.deferno.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Observable state of the [[Profile]] Destination's **identity card** (#70): the result of resolving
 * the Active [[Account]]'s `/auth/me` — the [[User]] it signs in as. The View renders this and the
 * co-located Account controls; it holds no business logic (ADR-0003/0007).
 *
 * The shape mirrors `feature/auth`'s tracer (the same one-shot `/auth/me` fetch), kept as its own type
 * so the Profile View doesn't depend on the auth slice.
 */
sealed interface ProfileState {
    /** The fetch is in flight (the initial state, and while a retry runs). */
    data object Loading : ProfileState

    /** The Active Account's identity resolved — the [user] the card renders. */
    data class SignedIn(val user: User) : ProfileState

    /**
     * The Active Account's credential is invalid/expired (a `401`). The repository has already raised
     * a re-auth request scoped to this Account (ADR-0002); the card prompts a sign-in retry.
     */
    data object ReauthRequired : ProfileState

    /** A transient failure (offline, server error). A retry is the right response — the Account stays signed in. */
    data object Unavailable : ProfileState
}

/**
 * The **Profile** Destination component (#70, ADR-0013): on creation it fetches the Active Account's
 * `/auth/me` via [AuthRepository] and exposes the outcome as observable [state] — the **display
 * identity of the [[User]]** the Active Account signs in as. Alongside it the View renders the
 * **Account controls** ([account] — the Active Account this Profile is bound to) and **Sign out**.
 *
 * Sign out is a **host concern**, not the feature's: [onSignOut] emits [Output.SignOutRequested] and
 * the shell performs the secure-wipe (`AccountManager.removeAccount`, ADR-0009/0012) and the return to
 * the Auth shell — the same Output-up routing Plan/Tasks use for cross-feature intents. This keeps the
 * Account-lifecycle mutation where account switching already lives (the [[Shell]]), and keeps the
 * Profile slice Compose-free and iOS-capable.
 *
 * A one-shot fetch (not a local `Flow`) because there is no `/auth/me` cache yet — the identity is
 * resolved live per scene (#20). [onRetry] re-runs it (after a transient failure or a re-auth prompt).
 */
interface ProfileComponent {
    /**
     * The Active [[Account]] this Profile is bound to — its label is the "active Account" control. A
     * static snapshot: switching the Active Account re-keys the whole Main shell (ADR-0014), rebuilding
     * this component, so it never changes underneath a live Profile.
     */
    val account: Account

    val state: StateFlow<ProfileState>

    /** Re-fetches `/auth/me` — e.g. the user tapped "try again" / "sign in again". */
    fun onRetry()

    /**
     * Begin sign-out for [account]: emits [Output.SignOutRequested] for the host to revoke + secure-wipe
     * and return to the Auth shell. The View is expected to confirm first (a destructive action).
     */
    fun onSignOut()

    sealed interface Output {
        /** The user asked to sign out of the Active Account; the host secure-wipes it (ADR-0009/0012). */
        data object SignOutRequested : Output
    }
}

class DefaultProfileComponent(
    componentContext: ComponentContext,
    private val authRepository: AuthRepository,
    override val account: Account,
    private val output: (ProfileComponent.Output) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : ProfileComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    private val _state = MutableStateFlow<ProfileState>(ProfileState.Loading)
    override val state: StateFlow<ProfileState> = _state.asStateFlow()

    init {
        load()
    }

    override fun onRetry() {
        load()
    }

    override fun onSignOut() {
        output(ProfileComponent.Output.SignOutRequested)
    }

    private fun load() {
        scope.launch {
            _state.value = ProfileState.Loading
            _state.value = when (val result = authRepository.loadMe()) {
                is MeResult.Authenticated -> ProfileState.SignedIn(result.user)
                MeResult.Unauthorized -> ProfileState.ReauthRequired
                MeResult.Unavailable -> ProfileState.Unavailable
            }
        }
    }
}
