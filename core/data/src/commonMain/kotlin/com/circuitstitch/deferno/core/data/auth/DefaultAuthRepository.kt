package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.data.account.AccountContext
import com.circuitstitch.deferno.core.data.account.ReauthRequester

/**
 * Default [AuthRepository] (#20): fetches `/auth/me` through the [remoteSource] and, when the result
 * is [MeResult.Unauthorized], routes the **Active Account** to re-auth via the [reauth] requester.
 *
 * "For that Account only" (ADR-0002) falls out of the design: it resolves who is active *now* through
 * the narrow read-only [AccountContext] and flags exactly that [AccountId], so a `401` can never sign
 * out a sibling Account. It depends on the producer-only [ReauthRequester], never the observable side,
 * so it can raise a request but not consume the stream. The [MeResult] is returned unchanged for the
 * component to render — the re-auth routing is a side effect, not a substitute for the result.
 *
 * The defensive `?.let` covers the (unreachable in normal flow) case of a 401 with no Active Account:
 * a request only carries a PAT when an Account is active, so a 401 implies one — but rather than
 * assert, a missing active Account simply raises no request (nothing to re-auth).
 */
class DefaultAuthRepository(
    private val remoteSource: AuthRemoteSource,
    private val accountContext: AccountContext,
    private val reauth: ReauthRequester,
) : AuthRepository {

    override suspend fun loadMe(): MeResult {
        val result = remoteSource.fetchMe()
        if (result is MeResult.Unauthorized) {
            accountContext.activeAccount.value?.let { account -> reauth.requestReauth(account.id) }
        }
        return result
    }
}
