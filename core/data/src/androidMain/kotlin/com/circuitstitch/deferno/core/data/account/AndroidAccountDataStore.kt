package com.circuitstitch.deferno.core.data.account

import android.content.Context
import com.circuitstitch.deferno.core.database.DatabaseKeyStore
import com.circuitstitch.deferno.core.database.databaseFileName
import com.circuitstitch.deferno.core.model.AccountId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android [AccountDataStore]: secure-wipes one Account's on-device data (ADR-0002 / ADR-0009) — its
 * per-Account encrypted SQLDelight database file (and SQLite sidecars) plus its database key. The
 * bearer token is wiped separately by [DefaultAccountManager.removeAccount] via the SecretVault.
 *
 * Deletes across both possible locations: the standard databases dir ([Context.deleteDatabase]) and
 * the no-backup dir, where the encrypted DB actually lives (the driver opens with
 * `useNoBackupDirectory = true`). Runs only on a device → excluded from the headless coverage gate.
 */
class AndroidAccountDataStore(
    private val context: Context,
    private val keyStore: DatabaseKeyStore,
) : AccountDataStore {

    override suspend fun wipe(account: AccountId): Unit = withContext(Dispatchers.IO) {
        val name = databaseFileName(account)
        // Standard databases dir (also removes -journal/-wal/-shm sidecars).
        context.deleteDatabase(name)
        // No-backup dir (useNoBackupDirectory = true), plus its sidecars.
        val noBackup = context.noBackupFilesDir
        listOf(name, "$name-journal", "$name-wal", "$name-shm").forEach { File(noBackup, it).delete() }
        keyStore.deleteKey(account)
    }
}
