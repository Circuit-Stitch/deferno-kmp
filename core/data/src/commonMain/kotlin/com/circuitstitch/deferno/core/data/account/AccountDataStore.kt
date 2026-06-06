package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.AccountId

/**
 * The per-Account local-data seam (ADR-0002 hard isolation): represents everything one Account
 * stores on the device, so the system can destroy it independently of every other Account. In v1
 * that data is the per-Account encrypted SQLDelight database plus its device-bound key (ADR-0009);
 * that real store does not exist yet, so this port carries only the operation [AccountManager]
 * needs today — [wipe].
 *
 * Secure-wipe on removal (ADR-0009): [AccountManager.removeAccount] calls [wipe] so an Account's
 * data is destroyed alongside its vaulted token. A mere `401` must NOT wipe — the cache is retained
 * and the Account re-authenticates. The real implementation (delete the SQLCipher file + destroy
 * its key) drops in behind this interface with no manager change; the port grows open/handle
 * accessors when real repositories need to read through it.
 */
interface AccountDataStore {
    /** Destroys all local data for [account] (secure-wipe). A no-op if there is nothing stored. */
    suspend fun wipe(account: AccountId)
}
