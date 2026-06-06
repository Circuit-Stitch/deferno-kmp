package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId

/**
 * Non-persistent [AccountRegistry] backed by an in-memory map — the stand-in that lets the
 * [AccountManager] logic above it run on the pure-JVM fast path (ADR-0006), and the documented
 * placeholder until a persistent registry (SQLDelight / preferences) lands. It holds nothing
 * across process restarts and so is NOT the production roster.
 *
 * Not thread-safe; drive it from a single context (as [DefaultAccountManager] does). Mirrors the
 * role InMemorySecretVault plays in `core:secure`.
 */
class InMemoryAccountRegistry : AccountRegistry {
    private val accountsById = LinkedHashMap<AccountId, Account>()
    private var activeId: AccountId? = null

    override suspend fun all(): List<Account> = accountsById.values.toList()

    override suspend fun put(account: Account) {
        accountsById[account.id] = account
    }

    override suspend fun remove(id: AccountId) {
        accountsById.remove(id)
    }

    override suspend fun activeId(): AccountId? = activeId

    override suspend fun setActive(id: AccountId?) {
        activeId = id
    }
}
