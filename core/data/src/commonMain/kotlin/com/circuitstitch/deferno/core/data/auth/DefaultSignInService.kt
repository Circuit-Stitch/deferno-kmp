package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Production [SignInService] (#15, ADR-0023): validate-then-commit over the [AuthRemoteSource]'s
 * candidate-token `/auth/me` and the [AccountManager]'s add-account seam — the convergence point the
 * dev-paste path and the future browser-mint path (ADR-0012) both land on. It holds no token itself:
 * the pasted PAT flows through validation into the secure vault (via `addAccount`) and is never
 * logged (ADR-0009).
 */
class DefaultSignInService(
    private val remoteSource: AuthRemoteSource,
    private val accountManager: AccountManager,
) : SignInService {

    override suspend fun signIn(token: String): SignInResult =
        when (val result = remoteSource.fetchMe(token)) {
            is MeResult.Authenticated -> {
                val user = result.user
                // AccountId = backend User id (ADR-0023): one Account ⇄ one User, so re-pasting the
                // same identity upserts rather than duplicating. The label is the human-facing name,
                // falling back to the login handle when the display name is blank.
                val account = Account(
                    id = AccountId(user.id.value),
                    label = user.displayName.ifBlank { user.username },
                )
                // Establishing the Account writes the verified token to the secure vault + provisions
                // the per-Account store. If that local infrastructure throws (e.g. the Keychain/Keystore
                // rejects the write — an unsigned iOS build has no Keychain entitlement, so SecItem*
                // returns errSecMissingEntitlement), it is a transient *local* failure, not an invalid
                // token: surface Unavailable so the View shows "try again" rather than letting the
                // exception escape the sign-in coroutine and abort the app (ADR-0009/0023). Cancellation
                // must still propagate so a torn-down component stops cleanly.
                try {
                    accountManager.addAccount(account, token)
                    SignInResult.Success(account)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    SignInResult.Unavailable
                }
            }
            // A 401 means the pasted PAT is invalid/expired; anything else is transient. Neither
            // creates an Account — only a verified token is stored (ADR-0023).
            MeResult.Unauthorized -> SignInResult.InvalidToken
            MeResult.Unavailable -> SignInResult.Unavailable
        }
}
