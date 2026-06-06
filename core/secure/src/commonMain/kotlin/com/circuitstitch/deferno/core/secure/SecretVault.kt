package com.circuitstitch.deferno.core.secure

import kotlin.jvm.JvmInline

/**
 * Stable identifier of an Account (ADR-0002) — the key under which that Account's bearer
 * token is vaulted. The Account is the hard isolation boundary: each one's secret is stored,
 * read, and wiped independently, and nothing ever crosses between them.
 */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "AccountId must not be blank" }
    }
}

/**
 * Capability port for at-rest secrets (ADR-0009): holds one bearer token per [AccountId] in
 * platform-backed secure storage — Android Keystore-wrapped AES-GCM, the iOS Keychain
 * (ThisDeviceOnly, no iCloud sync), or the desktop OS keychain. The wrapping keys are
 * device-bound and non-exportable, and the store is a cache excluded from OS backup: a new
 * device re-authenticates rather than restoring the token.
 *
 * Account-isolated (ADR-0002): each Account's token is independent. Implementations must
 * never log the values that pass through them. [InMemorySecretVault] is a non-persistent
 * implementation for tests and previews; the platform actuals are the production stores.
 */
interface SecretVault {
    /** Stores (replacing any existing) the bearer [token] for [account]. */
    fun putBearerToken(account: AccountId, token: String)

    /** Returns the stored bearer token for [account], or `null` if none is stored. */
    fun getBearerToken(account: AccountId): String?

    /** Removes [account]'s bearer token (secure-wipe on Account removal). A no-op if absent. */
    fun deleteBearerToken(account: AccountId)
}

/** Signals that the platform secure store could not complete an operation. */
class SecureStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)
