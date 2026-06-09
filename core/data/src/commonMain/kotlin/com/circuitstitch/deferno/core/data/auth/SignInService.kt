package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.model.Account

/**
 * The v1 sign-in seam (#15, ADR-0023): validate a pasted **personal access token** and, only if it
 * checks out, establish the [[Account]]. The system-browser OAuth + PKCE *minting* UX is gated on the
 * backend code→bearer exchange (Deferno#299) and stacks onto this same seam when it lands; v1 ships
 * the **paste path** as the real sign-in surface (ADR-0012's manual-paste convergence, productized).
 *
 * [DefaultSignInService] is the production implementation; the feature/signin component depends only
 * on this interface and maps the [SignInResult] to its observable state.
 */
interface SignInService {
    /**
     * Verify [token] against `GET /auth/me` (an explicit bearer, so no Active Account is required),
     * and **on success** create the Account — its id derived from the backend User id (so re-pasting
     * the same identity is an idempotent upsert, never a duplicate), its label from the User's display
     * name — store the token in the secure vault via `AccountManager.addAccount`, and make it active.
     * The shell observes the resulting active-Account change and swaps Auth → Main (ADR-0013).
     */
    suspend fun signIn(token: String): SignInResult
}

/**
 * The outcome of a sign-in attempt — three disjoint cases the surface reacts to differently:
 *
 * - [Success] — the [account] was created, its PAT vaulted, and it is now the Active Account.
 * - [InvalidToken] — the pasted token was rejected by `/auth/me` (a `401`): a typo or an
 *   expired/revoked PAT. The user fixes the token and retries; **no Account is created.**
 * - [Unavailable] — a transient failure (offline, server/transport error): not a credential
 *   problem, so a plain retry is the right response. **No Account is created.**
 */
sealed interface SignInResult {
    data class Success(val account: Account) : SignInResult
    data object InvalidToken : SignInResult
    data object Unavailable : SignInResult
}
