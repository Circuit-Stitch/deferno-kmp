package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.data.auth.AuthRemoteSource
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.secure.SecretVault
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default [AccountManager]: coordinates the per-Account collaborators — the [registry] roster, the
 * [vault] of bearer tokens, and the per-Account [dataStore] — and owns the observable
 * Active-Account / accounts state. The process-global singleton (ADR-0008 G2); also the
 * [AccountContext] that repositories and the network / secure-store layers resolve the Active
 * Account through.
 *
 * The StateFlows are an in-memory read model written through on every mutation alongside the
 * [registry] (the source of truth). They start empty; [load] hydrates them from the [registry] at
 * startup, so a persistent registry's roster + Active Account survive a cold start (ADR-0014).
 *
 * Not thread-safe; drive it from a single context (as its in-memory collaborators require).
 */
class DefaultAccountManager(
    private val registry: AccountRegistry,
    private val vault: SecretVault,
    private val dataStore: AccountDataStore,
    // Lazy to break the DI cycle: AuthRemoteSource → HttpClient → BearerTokenProvider → AccountContext
    // (= this manager). Only touched by removeAccount's best-effort revoke, long after the graph is
    // built. Defaults to an unwired stub so tests that never revoke a token-id-bearing account can omit
    // it; production binds the real one (DataBindings).
    private val authRemoteSource: Lazy<AuthRemoteSource> =
        lazy { error("AuthRemoteSource not wired for token revoke (ADR-0026)") },
) : AccountManager {

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeAccount = MutableStateFlow<Account?>(null)
    override val activeAccount: StateFlow<Account?> = _activeAccount.asStateFlow()

    override suspend fun addAccount(account: Account, token: String) {
        vault.putBearerToken(account.id, token)
        registry.put(account)
        if (registry.activeId() == null) registry.setActive(account.id)
        syncFromRegistry()
    }

    override suspend fun removeAccount(id: AccountId) {
        // Best-effort server-side revoke for accounts whose token id we know (browser-minted, ADR-0026),
        // BEFORE the local wipe — so the credential dies on the server, not just on the device. Pasted /
        // dev accounts carry no token id → local-wipe-only (their shared dev PAT must NOT be revoked).
        // A revoke failure (offline, already-revoked) must never block sign-out, so it is swallowed.
        val tokenId = registry.all().firstOrNull { it.id == id }?.tokenId
        if (tokenId != null) {
            val token = vault.getBearerToken(id)
            if (token != null) {
                try {
                    authRemoteSource.value.revokeToken(tokenId, token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Swallow — local wipe still proceeds. (revokeToken itself is already best-effort.)
                }
            }
        }

        // Secure-wipe data + token before deregistering, so a crash cannot orphan secrets (ADR-0009).
        dataStore.wipe(id)
        vault.deleteBearerToken(id)

        val wasActive = registry.activeId() == id
        registry.remove(id)
        if (wasActive) {
            registry.setActive(registry.all().firstOrNull()?.id)
        }
        syncFromRegistry()
    }

    override suspend fun switchTo(id: AccountId) {
        require(registry.all().any { it.id == id }) {
            "Cannot switch to unknown account: ${id.value}"
        }
        registry.setActive(id)
        syncFromRegistry()
    }

    override suspend fun load() = syncFromRegistry()

    /** Re-reads the roster + active selection from the [registry] into the StateFlows. */
    private suspend fun syncFromRegistry() {
        val all = registry.all()
        _accounts.value = all
        val activeId = registry.activeId()
        // firstOrNull coerces a dangling active id (one absent from the roster) to "no active
        // account". The manager keeps the active id pointing at a registered Account, so this is
        // unreachable today; self-healing a stale id is deferred to when a persistent registry —
        // where the case could actually arise — lands.
        _activeAccount.value = all.firstOrNull { it.id == activeId }
    }
}
