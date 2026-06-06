package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.secure.SecretVault
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
 * [registry] (the source of truth). The roster starts empty; once a persistent [AccountRegistry]
 * lands, a startup load of an existing roster into the StateFlows arrives with it (and its caller).
 *
 * Not thread-safe; drive it from a single context (as its in-memory collaborators require).
 */
class DefaultAccountManager(
    private val registry: AccountRegistry,
    private val vault: SecretVault,
    private val dataStore: AccountDataStore,
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
