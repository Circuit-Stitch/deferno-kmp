package com.circuitstitch.deferno.core.data.account

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.auth.FakeAuthRemoteSource
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.secure.InMemorySecretVault
import com.circuitstitch.deferno.core.secure.SecretVault
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of [DefaultAccountManager] over the in-memory collaborators (issue #14), on the
 * JVM-fast path (ADR-0006). Covers the acceptance criteria: add / remove / list / switch
 * (ADR-0002), per-Account token + data isolation with secure-wipe on removal (ADR-0009),
 * the observable Active Account, and that switching re-points collaborators resolved through the
 * [AccountContext] seam. Account-context resolution *per scene scope* (the DI graph) is covered by
 * core:di's ScopeGraphTest.
 */
class DefaultAccountManagerTest {
    private val accountA = Account(AccountId("account-a"), "Work")
    private val accountB = Account(AccountId("account-b"), "Personal")

    private lateinit var registry: InMemoryAccountRegistry
    private lateinit var vault: SecretVault
    private lateinit var dataStore: FakeAccountDataStore
    private lateinit var manager: DefaultAccountManager

    @BeforeTest
    fun setUp() {
        registry = InMemoryAccountRegistry()
        vault = InMemorySecretVault()
        dataStore = FakeAccountDataStore()
        manager = DefaultAccountManager(registry, vault, dataStore)
    }

    // --- AC#1: add / list ---

    @Test
    fun addThenListSurfacesTheAccountAndVaultsItsToken() = runTest {
        manager.addAccount(accountA, "token-a")

        assertContentEquals(listOf(accountA), manager.accounts.value)
        assertEquals("token-a", vault.getBearerToken(accountA.id))
    }

    @Test
    fun firstAddedAccountBecomesActive() = runTest {
        manager.addAccount(accountA, "token-a")

        assertEquals(accountA, manager.activeAccount.value)
    }

    @Test
    fun addingASecondAccountDoesNotChangeTheActiveAccount() = runTest {
        manager.addAccount(accountA, "token-a")
        manager.addAccount(accountB, "token-b")

        assertEquals(accountA, manager.activeAccount.value)
        assertContentEquals(listOf(accountA, accountB), manager.accounts.value)
    }

    @Test
    fun reAddingAnExistingIdReplacesTokenAndLabelWithoutDuplicating() = runTest {
        manager.addAccount(accountA, "old")
        val renamed = accountA.copy(label = "Work (renamed)")
        manager.addAccount(renamed, "new")

        assertContentEquals(listOf(renamed), manager.accounts.value)
        assertEquals("new", vault.getBearerToken(accountA.id))
    }

    @Test
    fun reAddingAnExistingAccountKeepsItsPositionAmongOthers() = runTest {
        manager.addAccount(accountA, "token-a")
        manager.addAccount(accountB, "token-b")

        val renamed = accountA.copy(label = "Work (renamed)")
        manager.addAccount(renamed, "token-a2")

        // Upsert in place: A keeps its slot, no duplicate, order preserved (AccountRegistry.all() contract).
        assertContentEquals(listOf(renamed, accountB), manager.accounts.value)
    }

    // --- AC#1: switch + AC#3: observable ---

    @Test
    fun switchToChangesTheObservableActiveAccount() = runTest {
        manager.addAccount(accountA, "token-a")
        manager.addAccount(accountB, "token-b")

        manager.switchTo(accountB.id)

        assertEquals(accountB, manager.activeAccount.value)
    }

    @Test
    fun activeAccountEmitsAcrossAddAndSwitch() = runTest {
        manager.activeAccount.test {
            assertNull(awaitItem()) // initial: none active
            manager.addAccount(accountA, "token-a")
            assertEquals(accountA, awaitItem()) // first add activates
            manager.addAccount(accountB, "token-b") // active unchanged → no emission
            manager.switchTo(accountB.id)
            assertEquals(accountB, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun switchToUnknownAccountThrows() = runTest {
        manager.addAccount(accountA, "token-a")

        assertFailsWith<IllegalArgumentException> { manager.switchTo(accountB.id) }
    }

    // --- AC#2: removal secure-wipes token + data; hard isolation ---

    @Test
    fun removeWipesTokenAndDataAndDeregistersLeavingOthersIntact() = runTest {
        manager.addAccount(accountA, "token-a")
        manager.addAccount(accountB, "token-b")
        dataStore.seed(accountA.id)
        dataStore.seed(accountB.id)

        manager.removeAccount(accountA.id)

        // A is fully wiped...
        assertNull(vault.getBearerToken(accountA.id))
        assertFalse(dataStore.hasData(accountA.id))
        assertTrue(accountA.id in dataStore.wiped)
        assertContentEquals(listOf(accountB), manager.accounts.value)
        // ...while B is untouched (ADR-0002 hard isolation).
        assertEquals("token-b", vault.getBearerToken(accountB.id))
        assertTrue(dataStore.hasData(accountB.id))
    }

    @Test
    fun removingTheActiveAccountRePointsActiveToARemainingAccount() = runTest {
        manager.addAccount(accountA, "token-a") // active
        manager.addAccount(accountB, "token-b")

        manager.removeAccount(accountA.id)

        assertEquals(accountB, manager.activeAccount.value)
    }

    @Test
    fun removingASwitchedToActiveAccountRePointsToARemainingAccount() = runTest {
        // The realistic flow: switch to a non-first account, then remove it (AC#1 switch ∩ AC#3 re-point).
        manager.addAccount(accountA, "token-a")
        manager.addAccount(accountB, "token-b")
        manager.switchTo(accountB.id)

        manager.removeAccount(accountB.id)

        assertEquals(accountA, manager.activeAccount.value)
        assertContentEquals(listOf(accountA), manager.accounts.value)
    }

    @Test
    fun removingTheLastAccountLeavesNoneActive() = runTest {
        manager.addAccount(accountA, "token-a")

        manager.removeAccount(accountA.id)

        assertNull(manager.activeAccount.value)
        assertTrue(manager.accounts.value.isEmpty())
    }

    @Test
    fun removingAnUnknownAccountIsANoOp() = runTest {
        manager.addAccount(accountA, "token-a")

        manager.removeAccount(accountB.id) // never added

        assertContentEquals(listOf(accountA), manager.accounts.value)
        assertEquals(accountA, manager.activeAccount.value)
    }

    // --- server-side token revoke on sign-out (ADR-0026 / #310) ---

    @Test
    fun removingAnAccountWithATokenIdRevokesItServerSideBeforeLocalWipe() = runTest {
        val remote = FakeAuthRemoteSource(MeResult.Unavailable)
        val mgr = DefaultAccountManager(registry, vault, dataStore, lazyOf(remote))
        val browserMinted = Account(AccountId("account-a"), "Work", tokenId = "tok-123")
        mgr.addAccount(browserMinted, "pat-a")

        mgr.removeAccount(browserMinted.id)

        // The known token id + its token were sent for server-side revoke...
        assertEquals(1, remote.revokeCalls)
        assertEquals("tok-123", remote.lastRevokedTokenId)
        assertEquals("pat-a", remote.lastRevokedToken)
        // ...and the local wipe still happened.
        assertNull(vault.getBearerToken(browserMinted.id))
        assertTrue(mgr.accounts.value.isEmpty())
    }

    @Test
    fun removingAnAccountWithoutATokenIdDoesNotRevokeServerSide() = runTest {
        // Paste / dev accounts carry no token id → local-wipe-only (the shared dev PAT must survive).
        val remote = FakeAuthRemoteSource(MeResult.Unavailable)
        val mgr = DefaultAccountManager(registry, vault, dataStore, lazyOf(remote))
        mgr.addAccount(accountA, "token-a") // accountA has no tokenId

        mgr.removeAccount(accountA.id)

        assertEquals(0, remote.revokeCalls)
        assertNull(vault.getBearerToken(accountA.id))
    }

    @Test
    fun aFailedServerRevokeStillCompletesLocalSignOut() = runTest {
        val remote = FakeAuthRemoteSource(MeResult.Unavailable).apply { revokeResult = false }
        val mgr = DefaultAccountManager(registry, vault, dataStore, lazyOf(remote))
        val browserMinted = Account(AccountId("account-a"), "Work", tokenId = "tok-123")
        mgr.addAccount(browserMinted, "pat-a")

        mgr.removeAccount(browserMinted.id)

        assertEquals(1, remote.revokeCalls)
        assertNull(vault.getBearerToken(browserMinted.id)) // local wipe proceeded despite revoke failure
        assertTrue(mgr.accounts.value.isEmpty())
    }

    // --- startup load: persisted roster hydrates the observable state (ADR-0014) ---

    @Test
    fun loadHydratesObservableStateFromThePersistedRegistry() = runTest {
        // Simulate a roster persisted by a previous process, behind the same registry.
        registry.put(accountA)
        registry.put(accountB)
        registry.setActive(accountB.id)

        // A freshly-built manager over that registry starts empty until loaded.
        val freshManager = DefaultAccountManager(registry, vault, dataStore)
        assertTrue(freshManager.accounts.value.isEmpty())
        assertNull(freshManager.activeAccount.value)

        freshManager.load()

        assertContentEquals(listOf(accountA, accountB), freshManager.accounts.value)
        assertEquals(accountB, freshManager.activeAccount.value)
    }

    // --- AC#3: switching re-points repositories + secure store (via the AccountContext seam) ---

    @Test
    fun switchingRePointsASecureStoreReaderThroughTheContext() = runTest {
        manager.addAccount(accountA, "token-a")
        manager.addAccount(accountB, "token-b")
        val tokenReader = FakeAccountScopedTokenReader(manager, vault)

        manager.switchTo(accountA.id)
        assertEquals("token-a", tokenReader.currentToken())

        manager.switchTo(accountB.id)
        assertEquals("token-b", tokenReader.currentToken()) // same reader, re-pointed
    }

    @Test
    fun switchingRePointsARepositoryThroughTheContext() = runTest {
        manager.addAccount(accountA, "token-a")
        manager.addAccount(accountB, "token-b")
        val repo = FakeAccountScopedRepository(manager)

        manager.switchTo(accountA.id)
        assertEquals("Work", repo.currentLabel())

        manager.switchTo(accountB.id)
        assertEquals("Personal", repo.currentLabel())
    }
}

/**
 * Stand-in for the future network token interceptor: resolves the bearer token for whoever is the
 * Active Account *at call time* via the [AccountContext] seam, so a switch re-points it without
 * reconstruction (AC#3: "switching re-points the secure store").
 */
private class FakeAccountScopedTokenReader(
    private val context: AccountContext,
    private val vault: SecretVault,
) {
    fun currentToken(): String? = context.activeAccount.value?.let { vault.getBearerToken(it.id) }
}

/**
 * Stand-in for a future per-Account repository: reads from whoever is the Active Account at call
 * time via the [AccountContext] seam (here, its label), so a switch re-points it (AC#3: "switching
 * re-points repositories"). Real repositories will resolve their per-Account database the same way.
 */
private class FakeAccountScopedRepository(
    private val context: AccountContext,
) {
    fun currentLabel(): String? = context.activeAccount.value?.label
}
