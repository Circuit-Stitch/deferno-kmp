package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.AccountId

/**
 * Test [AccountDataStore] that tracks which Accounts have local data and records every wipe, so
 * tests can assert removal secure-wipes the right Account's data while leaving the others intact
 * (ADR-0002 hard isolation). Stands in for the per-Account encrypted database on the JVM-fast path.
 */
class FakeAccountDataStore : AccountDataStore {
    private val withData = mutableSetOf<AccountId>()

    /** Every id passed to [wipe], in order — lets a test assert the manager wiped the right Account. */
    val wiped = mutableListOf<AccountId>()

    /** Simulates a populated per-Account store, so a later [wipe] is observable. */
    fun seed(account: AccountId) {
        withData += account
    }

    /** True while [account] still has local data — i.e. it has not been wiped. */
    fun hasData(account: AccountId): Boolean = account in withData

    override suspend fun wipe(account: AccountId) {
        withData -= account
        wiped += account
    }
}
