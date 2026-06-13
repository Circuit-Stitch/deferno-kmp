package com.circuitstitch.deferno.core.database.driver

import com.circuitstitch.deferno.core.database.DatabaseKeyException
import com.circuitstitch.deferno.core.database.DatabaseKeyStore
import com.circuitstitch.deferno.core.model.AccountId
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS [DatabaseKeyStore] (ADR-0002/0009): supplies each Account's SQLCipher passphrase, minting a
 * fresh high-entropy key on first use and persisting it in the Keychain as a
 * `kSecClassGenericPassword` item keyed by service + [AccountId]. Items are stored
 * `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` — device-bound, never synced to iCloud, never in
 * encrypted backups, so a restored device re-keys (its DB ciphertext is unreadable) and the Account
 * re-authenticates, mirroring [AndroidDatabaseKeyProvider]'s posture.
 *
 * AppScope process-singleton (ADR-0014): one per process, keyed per Account on each call. The key is
 * a String (SQLiter's `Encryption(key)` takes a String passphrase) — 256 random bits hex-encoded, so
 * it round-trips losslessly. Never logged (ADR-0009). Device/simulator only; excluded from the
 * headless coverage gate.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDatabaseKeyProvider(
    private val service: String = DEFAULT_SERVICE,
) : DatabaseKeyStore {

    override fun databaseKey(account: AccountId): String {
        read(account)?.let { return it }
        val key = mint()
        store(account, key)
        // Re-read so a concurrent first-use that already persisted a key wins consistently: whatever
        // is on the Keychain is the Account's one true passphrase.
        return read(account) ?: key
    }

    override fun deleteKey(account: AccountId) {
        withCfRefs {
            val query = track(
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to track(service.toCFString()),
                    kSecAttrAccount to track(account.value.toCFString()),
                ),
            )
            val status = SecItemDelete(query)
            if (status != errSecSuccess && status != errSecItemNotFound) {
                throw DatabaseKeyException("Keychain delete failed (OSStatus=$status)")
            }
        }
    }

    private fun read(account: AccountId): String? = withCfRefs {
        val query = track(
            cfDictionaryOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to track(service.toCFString()),
                kSecAttrAccount to track(account.value.toCFString()),
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            ),
        )
        memScoped {
            val result = alloc<CFTypeRefVar>()
            when (val status = SecItemCopyMatching(query, result.ptr)) {
                errSecSuccess -> result.value?.readCFDataAsString()
                errSecItemNotFound -> null
                else -> throw DatabaseKeyException("Keychain read failed (OSStatus=$status)")
            }
        }
    }

    private fun store(account: AccountId, key: String) {
        withCfRefs {
            val cfService = track(service.toCFString())
            val cfAccount = track(account.value.toCFString())
            val cfData = track(key.encodeToByteArray().toCFData())
            val addQuery = track(
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                    kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                    kSecValueData to cfData,
                ),
            )
            when (val status = SecItemAdd(addQuery, null)) {
                errSecSuccess -> {}
                errSecDuplicateItem -> {
                    val matchQuery = track(
                        cfDictionaryOf(
                            kSecClass to kSecClassGenericPassword,
                            kSecAttrService to cfService,
                            kSecAttrAccount to cfAccount,
                        ),
                    )
                    val update = track(cfDictionaryOf(kSecValueData to cfData))
                    val updateStatus = SecItemUpdate(matchQuery, update)
                    if (updateStatus != errSecSuccess) {
                        throw DatabaseKeyException("Keychain update failed (OSStatus=$updateStatus)")
                    }
                }
                else -> throw DatabaseKeyException("Keychain store failed (OSStatus=$status)")
            }
        }
    }

    /** A fresh 256-bit random passphrase, hex-encoded to a lossless String (SQLiter takes UTF-8). */
    private fun mint(): String = memScoped {
        val buffer = allocArray<UByteVar>(KEY_BYTES)
        val status = SecRandomCopyBytes(kSecRandomDefault, KEY_BYTES.convert(), buffer)
        if (status != errSecSuccess) {
            throw DatabaseKeyException("Secure random failed (OSStatus=$status)")
        }
        buffer.readBytes(KEY_BYTES).toHex()
    }

    private companion object {
        const val DEFAULT_SERVICE = "com.circuitstitch.deferno.dbkey"
        const val KEY_BYTES = 32
    }
}

private const val HEX = "0123456789abcdef"

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val b = byte.toInt() and 0xFF
        append(HEX[b ushr 4])
        append(HEX[b and 0x0F])
    }
}

/**
 * Tracks the CoreFoundation objects created during a Keychain call and releases them when it ends
 * (constants like `kSec*` are owned by the framework and must not be tracked). Mirrors the helper in
 * core:secure's KeychainSecretVault — kept module-local so core:database stays free of core:secure.
 */
@OptIn(ExperimentalForeignApi::class)
private class CfRefScope {
    private val refs = mutableListOf<CFTypeRef>()

    fun <T : CPointed> track(ref: CPointer<T>?): CPointer<T>? = ref?.also { refs.add(it) }

    fun releaseAll() = refs.forEach { CFRelease(it) }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <R> withCfRefs(block: CfRefScope.() -> R): R {
    val scope = CfRefScope()
    try {
        return scope.block()
    } finally {
        scope.releaseAll()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun cfDictionaryOf(vararg pairs: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? = memScoped {
    val keys = allocArrayOf(pairs.map { it.first })
    val values = allocArrayOf(pairs.map { it.second })
    CFDictionaryCreate(
        kCFAllocatorDefault,
        keys.reinterpret(),
        values.reinterpret(),
        pairs.size.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun String.toCFString(): CFStringRef? =
    CFStringCreateWithCString(kCFAllocatorDefault, this, kCFStringEncodingUTF8)

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef? =
    if (isEmpty()) {
        CFDataCreate(kCFAllocatorDefault, null, 0.convert())
    } else {
        usePinned { pinned ->
            CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), size.convert())
        }
    }

/** Reads a Keychain-returned `CFData` payload as a UTF-8 string and releases it. */
@OptIn(ExperimentalForeignApi::class)
private fun CFTypeRef.readCFDataAsString(): String {
    val data: CFDataRef = this.reinterpret()
    val length = CFDataGetLength(data)
    val bytePtr = CFDataGetBytePtr(data)
    val bytes = bytePtr?.reinterpret<ByteVar>()?.readBytes(length.convert()) ?: ByteArray(0)
    CFRelease(this)
    return bytes.decodeToString()
}
