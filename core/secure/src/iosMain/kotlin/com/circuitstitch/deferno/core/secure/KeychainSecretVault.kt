package com.circuitstitch.deferno.core.secure

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
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
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS [SecretVault]: each bearer token is a `kSecClassGenericPassword` Keychain item, keyed
 * by service + [AccountId]. Items are stored `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`
 * (ADR-0009) — device-bound, never synced to iCloud and never included in encrypted backups,
 * so a restored device re-authenticates rather than carrying the token across.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecretVault(
    private val service: String = DEFAULT_SERVICE,
) : SecretVault {

    override fun putBearerToken(account: AccountId, token: String) {
        withCfRefs {
            val cfService = track(service.toCFString())
            val cfAccount = track(account.value.toCFString())
            val cfData = track(token.encodeToByteArray().toCFData())
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
                // Replace the existing item's data in place rather than delete-then-add, so a
                // failed write never destroys the prior token (ADR-0009: retain on transient failure).
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
                        throw SecureStorageException("Keychain update failed (OSStatus=$updateStatus)")
                    }
                }
                else -> throw SecureStorageException("Keychain store failed (OSStatus=$status)")
            }
        }
    }

    override fun getBearerToken(account: AccountId): String? = withCfRefs {
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
                else -> throw SecureStorageException("Keychain read failed (OSStatus=$status)")
            }
        }
    }

    override fun deleteBearerToken(account: AccountId) {
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
                throw SecureStorageException("Keychain delete failed (OSStatus=$status)")
            }
        }
    }

    private companion object {
        const val DEFAULT_SERVICE = "com.circuitstitch.deferno.bearer"
    }
}

/**
 * Tracks the CoreFoundation objects created during a Keychain call and releases them when it
 * ends (constants like `kSec*` are owned by the framework and must not be tracked).
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
    // An empty array has no element to pin (addressOf(0) would throw), so create an empty
    // CFData directly — an empty token then round-trips like every other actual.
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
