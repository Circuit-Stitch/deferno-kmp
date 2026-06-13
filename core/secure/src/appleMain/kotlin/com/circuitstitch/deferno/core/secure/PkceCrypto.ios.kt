@file:OptIn(ExperimentalForeignApi::class)

package com.circuitstitch.deferno.core.secure

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal actual fun secureRandomBytes(byteCount: Int): ByteArray {
    if (byteCount == 0) return ByteArray(0)
    val out = ByteArray(byteCount)
    val status = out.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, byteCount.convert(), pinned.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed (OSStatus=$status)" }
    return out
}

internal actual fun sha256(input: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { out ->
        if (input.isEmpty()) {
            CC_SHA256(null, 0u, out.addressOf(0))
        } else {
            input.usePinned { inp ->
                CC_SHA256(inp.addressOf(0), input.size.convert(), out.addressOf(0))
            }
        }
    }
    return digest.toByteArray()
}
