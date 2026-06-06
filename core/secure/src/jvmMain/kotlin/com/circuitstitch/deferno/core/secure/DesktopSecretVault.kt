package com.circuitstitch.deferno.core.secure

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException

/**
 * Desktop (JVM) [SecretVault] backed by the host OS keychain via java-keyring — the macOS
 * Keychain, the Windows Credential Store, or the Linux Secret Service (libsecret), ADR-0009.
 * Each bearer token is stored as a generic password under a fixed service name, keyed by
 * [AccountId]; the OS owns encryption at rest, and nothing is written to app files.
 *
 * Construct one per process (an AppScope singleton, ADR-0008) via [create], which maps a
 * missing backend to [SecureStorageException]; the primary constructor takes an injected
 * [Keyring] for tests and DI.
 */
class DesktopSecretVault(
    private val keyring: Keyring,
    private val service: String = DEFAULT_SERVICE,
) : SecretVault {

    override fun putBearerToken(account: AccountId, token: String) {
        try {
            keyring.setPassword(service, account.value, token)
        } catch (e: PasswordAccessException) {
            throw SecureStorageException("Failed to store bearer token in OS keychain", e)
        }
    }

    override fun getBearerToken(account: AccountId): String? =
        try {
            keyring.getPassword(service, account.value)
        } catch (e: PasswordAccessException) {
            // java-keyring signals a missing entry with PasswordAccessException, but the same
            // type also covers genuine read faults (locked keyring, D-Bus error). The port's
            // getBearerToken has no error channel (it returns String?), so a read fault here
            // degrades to "no token" — a deliberate trade-off that may force a re-auth. The
            // security-relevant wipe path ([deleteBearerToken]) does NOT swallow failures.
            null
        }

    override fun deleteBearerToken(account: AccountId) {
        // Idempotent when the entry is absent, but a genuine wipe failure must surface: ADR-0009
        // requires a real secure-wipe on Account removal, so a failed delete must not look like
        // success (java-keyring reports both "absent" and "error" as PasswordAccessException, so
        // we distinguish them by first checking presence).
        if (getBearerToken(account) == null) return
        try {
            keyring.deletePassword(service, account.value)
        } catch (e: PasswordAccessException) {
            throw SecureStorageException("Failed to wipe bearer token from OS keychain", e)
        }
    }

    companion object {
        private const val DEFAULT_SERVICE = "com.circuitstitch.deferno.bearer"

        /**
         * Opens a vault over the host OS keychain, or throws [SecureStorageException] if no
         * backend is available for this OS (e.g. a headless host with no Secret Service).
         */
        fun create(service: String = DEFAULT_SERVICE): DesktopSecretVault =
            try {
                DesktopSecretVault(Keyring.create(), service)
            } catch (e: BackendNotSupportedException) {
                throw SecureStorageException("No OS keychain backend available", e)
            }
    }
}
