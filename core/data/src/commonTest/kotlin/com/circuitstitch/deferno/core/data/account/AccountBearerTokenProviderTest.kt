package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.secure.InMemorySecretVault
import com.circuitstitch.deferno.core.secure.SecretVault
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AccountBearerTokenProvider] over the real [DefaultAccountManager] + [InMemorySecretVault]
 * (issue #17): the bearer is the Active Account's vaulted PAT, resolved at call time so an
 * account switch / removal re-points it without rebuilding (ADR-0002).
 */
class AccountBearerTokenProviderTest {
    private val accountA = Account(AccountId("account-a"), "Work")
    private val accountB = Account(AccountId("account-b"), "Personal")

    private lateinit var vault: SecretVault
    private lateinit var manager: DefaultAccountManager
    private lateinit var provider: AccountBearerTokenProvider

    @BeforeTest
    fun setUp() {
        vault = InMemorySecretVault()
        manager = DefaultAccountManager(InMemoryAccountRegistry(), vault, FakeAccountDataStore())
        provider = AccountBearerTokenProvider(manager, vault)
    }

    @Test
    fun returnsNullWhenNoAccountIsActive() = runTest {
        assertNull(provider.currentToken())
    }

    @Test
    fun returnsTheActiveAccountsVaultedToken() = runTest {
        manager.addAccount(accountA, "token-a")

        assertEquals("token-a", provider.currentToken())
    }

    @Test
    fun rePointsToTheNewActiveTokenAfterSwitch() = runTest {
        manager.addAccount(accountA, "token-a")
        manager.addAccount(accountB, "token-b")

        manager.switchTo(accountB.id)

        assertEquals("token-b", provider.currentToken())
    }

    @Test
    fun returnsNullAfterTheActiveAccountIsRemoved() = runTest {
        manager.addAccount(accountA, "token-a")

        manager.removeAccount(accountA.id)

        assertNull(provider.currentToken())
    }
}
