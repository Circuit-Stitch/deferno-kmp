package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.network.AuthSessionListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Raises a re-auth request: the Active Account's credential is no longer valid and it must sign in
 * again (#20, ADR-0012). The request is **scoped to one [AccountId]** by construction — a `401` on
 * the Active Account flags *only* that Account, never the others (ADR-0002 hard isolation: a credential
 * failure can't sign out a sibling identity). [DefaultAuthRepository] is the producer; the Auth shell
 * (ADR-0013) is the eventual consumer that re-enters the sign-in flow for the flagged Account.
 *
 * A narrow producer port (it only *emits*): a collaborator that detects an expired session depends on
 * this, not on the full observable [ReauthRequests], so it can't accidentally consume the stream.
 */
interface ReauthRequester {
    /** Signals that [account] needs to re-authenticate. Scoped to [account] alone (never global). */
    fun requestReauth(account: AccountId)
}

/**
 * The observable side of re-auth (#20): the stream of Accounts that have been flagged as needing
 * sign-in again. The Auth shell (ADR-0013) collects this to re-enter the auth flow for the named
 * Account, leaving every other Account untouched. Separated from [ReauthRequester] so a consumer
 * subscribes without gaining the ability to emit.
 */
interface ReauthRequests {
    /** Each emission is one Account ([AccountId]) that needs re-authentication, in request order. */
    val events: SharedFlow<AccountId>

    /**
     * Whether the **Active Account's** session is currently expired (#297) — the retained,
     * late-subscriber-friendly companion to the [events] stream. Set when an Active-Account request
     * `401`s (via [ReauthRequester.requestReauth] or the network [AuthSessionListener]); cleared when
     * an Active-Account request next succeeds (`2xx`). The read surfaces (Tasks / Search / Plan) render
     * a "Session expired — sign in again" banner off this so a dead token can't masquerade as a stale
     * cache. A `StateFlow` (not an event) precisely because a surface mounted *after* the 401 must still
     * see the banner — unlike [events], which the Auth shell consumes once.
     */
    val sessionExpired: StateFlow<Boolean>
}

/**
 * Default [ReauthCoordinator] — the process-global seam between "a request 401'd" and "the Auth shell
 * re-prompts that Account" (#20, ADR-0008 G2: the account boundary is an `AppScope` singleton). A
 * re-auth is an **event**, not retained state, so it is a buffered [MutableSharedFlow] with no replay:
 * a flag is delivered to whoever is listening, and a late subscriber does not re-trigger a stale
 * prompt. The buffer + `tryEmit` keep [requestReauth] non-suspending so it is callable from any
 * call site (e.g. inside a repository's request handling) without blocking.
 */
class DefaultReauthCoordinator : ReauthRequester, ReauthRequests, AuthSessionListener {
    private val _events = MutableSharedFlow<AccountId>(extraBufferCapacity = 16)
    override val events: SharedFlow<AccountId> = _events.asSharedFlow()

    private val _sessionExpired = MutableStateFlow(false)
    override val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    override fun requestReauth(account: AccountId) {
        _events.tryEmit(account)
        // An explicit re-auth request (the /auth/me 401 path) implies the session is dead — keep the
        // retained flag in step with the one-shot event so a read surface mounted later still banners.
        _sessionExpired.value = true
    }

    // The network sink (#297): the shared client reports every Active-Account request here.
    override fun onActiveSessionUnauthorized() {
        _sessionExpired.value = true
    }

    override fun onActiveSessionAuthorized() {
        // A successful Active-Account request clears the banner — so signing back in self-heals on the
        // first fresh sync, with no explicit "I signed in" wiring (AC #4).
        _sessionExpired.value = false
    }
}
