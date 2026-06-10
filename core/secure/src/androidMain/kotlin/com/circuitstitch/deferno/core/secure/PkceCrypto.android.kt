package com.circuitstitch.deferno.core.secure

import java.security.MessageDigest
import java.security.SecureRandom

internal actual fun secureRandomBytes(byteCount: Int): ByteArray =
    ByteArray(byteCount).also { SecureRandom().nextBytes(it) }

internal actual fun sha256(input: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input)
