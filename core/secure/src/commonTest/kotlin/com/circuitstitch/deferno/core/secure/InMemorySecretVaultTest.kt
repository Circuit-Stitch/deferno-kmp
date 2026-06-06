package com.circuitstitch.deferno.core.secure

import com.circuitstitch.deferno.core.model.AccountId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trip contract for the in-memory [SecretVault] fake (issue #13): store / load / delete
 * and Account isolation, on the pure-JVM fast path (ADR-0006). The platform actuals
 * (Keystore / Keychain / desktop OS keychain) are exercised by on-device & native tests.
 */
class InMemorySecretVaultTest {
    private val accountA = AccountId("account-a")
    private val accountB = AccountId("account-b")

    private lateinit var vault: SecretVault

    @BeforeTest
    fun setUp() {
        vault = InMemorySecretVault()
    }

    @Test
    fun getReturnsNullBeforeAnythingIsStored() {
        assertNull(vault.getBearerToken(accountA))
    }

    @Test
    fun storesThenLoadsABearerToken() {
        vault.putBearerToken(accountA, "token-a")
        assertEquals("token-a", vault.getBearerToken(accountA))
    }

    @Test
    fun overwritesAnExistingToken() {
        vault.putBearerToken(accountA, "old")
        vault.putBearerToken(accountA, "new")
        assertEquals("new", vault.getBearerToken(accountA))
    }

    @Test
    fun deleteRemovesTheToken() {
        vault.putBearerToken(accountA, "token-a")
        vault.deleteBearerToken(accountA)
        assertNull(vault.getBearerToken(accountA))
    }

    @Test
    fun deleteIsANoOpWhenAbsent() {
        vault.deleteBearerToken(accountA) // must not throw
        assertNull(vault.getBearerToken(accountA))
    }

    @Test
    fun accountsAreIsolated() {
        vault.putBearerToken(accountA, "token-a")
        vault.putBearerToken(accountB, "token-b")

        assertEquals("token-a", vault.getBearerToken(accountA))
        assertEquals("token-b", vault.getBearerToken(accountB))

        // Wiping one Account leaves the other untouched (ADR-0002 hard isolation).
        vault.deleteBearerToken(accountA)
        assertNull(vault.getBearerToken(accountA))
        assertEquals("token-b", vault.getBearerToken(accountB))
    }
}
