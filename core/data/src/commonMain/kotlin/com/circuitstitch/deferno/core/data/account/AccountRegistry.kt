package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId

/**
 * Durable roster of which [Account]s exist on this device and which one is active — the index a
 * future account switcher reads *before* any Account is active. It therefore lives outside any
 * per-Account encrypted database (ADR-0002) and holds only non-secret metadata: never bearer
 * tokens (those stay in the SecretVault).
 *
 * A capability port. [InMemoryAccountRegistry] is the non-persistent stand-in shipped now; a
 * persistent implementation (a small SQLDelight table or a preferences store) lands with the
 * database and drops in behind this interface with no [AccountManager] change.
 *
 * The registry is dumb storage — [AccountManager] owns the invariant that the active id always
 * points at a registered Account.
 */
interface AccountRegistry {
    /** All registered Accounts, in the order they were added. */
    suspend fun all(): List<Account>

    /** Inserts [account], or replaces the existing entry with the same id (upsert). */
    suspend fun put(account: Account)

    /** Removes the Account with [id]; a no-op if absent. Does not change the active id. */
    suspend fun remove(id: AccountId)

    /** The active Account's id, or `null` if none is active. */
    suspend fun activeId(): AccountId?

    /** Sets the active Account id, or clears it with `null`. */
    suspend fun setActive(id: AccountId?)
}
