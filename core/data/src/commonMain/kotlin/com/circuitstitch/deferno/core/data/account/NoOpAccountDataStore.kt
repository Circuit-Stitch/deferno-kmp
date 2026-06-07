package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.AccountId

/**
 * No-op [AccountDataStore] for platforms without a device-bound per-Account data wipe yet (desktop +
 * iOS, ADR-0014). Account removal still tears down the roster row and the vaulted token; on these
 * targets there is no separate encrypted-DB sidecar lifecycle to destroy beyond the DB file the
 * driver owns. Android uses [AndroidAccountDataStore] (the real secure-wipe).
 */
object NoOpAccountDataStore : AccountDataStore {
    override suspend fun wipe(account: AccountId) = Unit
}
