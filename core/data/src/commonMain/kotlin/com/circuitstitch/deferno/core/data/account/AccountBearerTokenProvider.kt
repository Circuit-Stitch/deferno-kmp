package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.network.BearerTokenProvider
import com.circuitstitch.deferno.core.secure.SecretVault

/**
 * The production [BearerTokenProvider] (issue #17, ADR-0012): resolves the bearer the network
 * client attaches to each request as **the Active Account's personal access token**. It is the
 * implementation of the `core:network` port that lives here in `core:data`, where the Active
 * Account ([AccountContext]) and the per-Account token store ([SecretVault]) are — `core:network`
 * sits below both and only declares the port (ADR-0004 layering).
 *
 * Both reads happen **at call time**, never cached: it asks [AccountContext] who is active right
 * now and reads *that* Account's token from the vault. So a fast user switch
 * ([AccountManager.switchTo], ADR-0002) re-points the credential with no client rebuild, and a
 * removed/absent Account yields `null` (the request goes out unauthenticated). It resolves the
 * narrow read-only [AccountContext], not the full add/remove/switch [AccountManager] — it only
 * reads the boundary. Never logs the token (ADR-0009).
 *
 * This is the real counterpart to the `FakeAccountScopedTokenReader` stand-in in
 * `DefaultAccountManagerTest`. Its DI binding (an `AppScope` singleton) is wired when the network
 * client itself is contributed to the graph; the DI graph is still empty in v1 (ADR-0008).
 */
class AccountBearerTokenProvider(
    private val accountContext: AccountContext,
    private val vault: SecretVault,
) : BearerTokenProvider {
    override fun currentToken(): String? =
        accountContext.activeAccount.value?.let { account -> vault.getBearerToken(account.id) }
}
