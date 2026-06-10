package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Persistent [AccountRegistry] backed by a single JSON file in Application Support — the iOS twin of
 * `SharedPreferencesAccountRegistry` (ADR-0002/0014): the durable roster + active-account selection
 * that survives process restarts, so a cold start restores the Active Account without
 * re-authenticating. Holds only **non-secret metadata** (ids + labels + token ids) via
 * [AccountRosterCodec]; bearer tokens stay in the Keychain vault, per-Account data in the encrypted DB.
 *
 * The file is marked [NSURLIsExcludedFromBackupKey] — the same posture as the Android backup-rule
 * excludes: the roster is a device-bound cache, and the Keychain tokens it references are
 * `ThisDeviceOnly`, so a restored device starts clean and re-authenticates (ADR-0009) instead of
 * surfacing accounts whose credentials didn't travel. Reads tolerate a missing/corrupt file (decoded
 * to an empty roster); write errors degrade silently to the same re-auth posture. Device-only IO —
 * the round-trip logic is measured via [AccountRosterCodec] in commonTest, the file IO in iosTest.
 */
@OptIn(ExperimentalForeignApi::class)
class FileAccountRegistry(
    private val directoryPath: String = defaultDirectoryPath(),
) : AccountRegistry {

    private val filePath: String get() = "$directoryPath/$FILE_NAME"

    override suspend fun all(): List<Account> = read().accounts

    override suspend fun put(account: Account) {
        val document = read()
        val roster = document.accounts.toMutableList()
        val at = roster.indexOfFirst { it.id == account.id }
        if (at >= 0) roster[at] = account else roster.add(account) // upsert, preserving position
        write(document.copy(accounts = roster))
    }

    override suspend fun remove(id: AccountId) {
        val document = read()
        write(document.copy(accounts = document.accounts.filterNot { it.id == id }))
    }

    override suspend fun activeId(): AccountId? = read().activeId

    override suspend fun setActive(id: AccountId?) {
        write(read().copy(activeId = id))
    }

    private fun read(): AccountRosterCodec.Document =
        AccountRosterCodec.decodeDocument(
            NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null),
        )

    private fun write(document: AccountRosterCodec.Document) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            directoryPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        (AccountRosterCodec.encodeDocument(document.accounts, document.activeId) as NSString)
            .writeToFile(filePath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        // Device-bound cache: keep the roster out of iCloud/iTunes backups (see class doc).
        NSURL.fileURLWithPath(filePath)
            .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    private companion object {
        const val FILE_NAME = "account_roster.json"

        fun defaultDirectoryPath(): String {
            val base = NSSearchPathForDirectoriesInDomains(
                NSApplicationSupportDirectory,
                NSUserDomainMask,
                true,
            ).firstOrNull() as? String ?: NSTemporaryDirectory()
            return "$base/deferno"
        }
    }
}
