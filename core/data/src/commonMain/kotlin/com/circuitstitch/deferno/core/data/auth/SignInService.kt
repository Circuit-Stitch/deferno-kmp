package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.model.Account

/**
 * The sign-in seam (#15, ADR-0012/0023/0026). Two paths converge on the same `AccountManager.addAccount`
 * commit (ADR-0012's "OAuth bootstraps, the PAT is the credential"):
 *
 * - [signInWithBrowser] — the **proper** native login: a system-browser OAuth Authorization Code + PKCE
 *   flow that mints a per-device PAT (backend #299). Password + MFA + SSO happen in the browser; the app
 *   ships zero credential UI.
 * - [signIn] — the **dev** paste path (ADR-0023): validate a hand-pasted PAT and store it. Kept as a
 *   developer affordance / MFA escape hatch behind a debug toggle, no longer the primary surface.
 *
 * [DefaultSignInService] is the production implementation; the feature/signin component depends only on
 * this interface and maps the [SignInResult] to its observable state.
 */
interface SignInService {
    /**
     * Runs the system-browser OAuth + PKCE flow (ADR-0026): register a client (cached), open the OS
     * browser at the authorize URL, capture the redirected one-time code, exchange it for a minted PAT,
     * then resolve identity via `/auth/me` and commit the Account (id = backend User id, the minted
     * token + its id vaulted, made active). The shell observes the active-Account change and swaps
     * Auth → Main (ADR-0013).
     */
    suspend fun signInWithBrowser(): SignInResult

    /**
     * Verify [token] against `GET /auth/me` (an explicit bearer, so no Active Account is required),
     * and **on success** create the Account — its id derived from the backend User id (so re-pasting
     * the same identity is an idempotent upsert, never a duplicate), its label from the User's display
     * name — store the token in the secure vault via `AccountManager.addAccount`, and make it active.
     * The pasted token carries no server-side id, so the Account is local-wipe-only (ADR-0023).
     */
    suspend fun signIn(token: String): SignInResult
}

/**
 * The outcome of a sign-in attempt — the disjoint cases the surface reacts to differently:
 *
 * - [Success] — the [account] was created, its PAT vaulted, and it is now the Active Account.
 * - [InvalidToken] — a pasted token was rejected by `/auth/me` (a `401`): a typo or an expired/revoked
 *   PAT (paste path only). The user fixes the token and retries; **no Account is created.**
 * - [Cancelled] — the user dismissed the browser before completing sign-in (browser path only). Not an
 *   error; the surface just returns to idle. **No Account is created.**
 * - [Unavailable] — a transient failure (offline, server/transport error, a tampered/failed redirect):
 *   not a credential problem, so a plain retry is the right response. **No Account is created.**
 */
sealed interface SignInResult {
    data class Success(val account: Account) : SignInResult
    data object InvalidToken : SignInResult
    data object Cancelled : SignInResult
    data object Unavailable : SignInResult
}
