package com.circuitstitch.deferno.core.secure

/**
 * Non-persistent [SecretVault] backed by an in-memory map — the fake that lets the auth and
 * Account logic above it run on the pure-JVM fast path (ADR-0006), plus a stand-in for
 * previews. It holds nothing across process restarts and gives no at-rest protection, so it
 * is NOT a production secret store; the platform actuals are.
 *
 * Not thread-safe; drive it from a single context (as the tests do).
 */
class InMemorySecretVault : SecretVault {
    private val tokensByAccount = mutableMapOf<String, String>()

    override fun putBearerToken(account: AccountId, token: String) {
        tokensByAccount[account.value] = token
    }

    override fun getBearerToken(account: AccountId): String? = tokensByAccount[account.value]

    override fun deleteBearerToken(account: AccountId) {
        tokensByAccount.remove(account.value)
    }
}
