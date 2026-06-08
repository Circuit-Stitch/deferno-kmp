package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.settings.SettingsWriter
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData
import com.circuitstitch.deferno.ui.FakeAuthRepository
import com.circuitstitch.deferno.ui.FakeSettingsRepository
import com.circuitstitch.deferno.ui.FakeSettingsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-scene navigation root (ADR-0013 / ADR-0014): the two-state Shell selected by the Active
 * Account, re-entrant in both directions, with system-back routed to the active Shell and a Tasks
 * "add to plan" applied through the Active Account's offline write path. Driven here by a fake
 * AccountManager + AccountSession (no real graph/network), with an Unconfined context so the
 * activeAccount collector reacts synchronously.
 */
class RootComponentTest {

    private val today = LocalDate(2026, 6, 6)
    private val account = Account(AccountId("work"), "Work")

    private fun root(
        manager: AccountManager,
        session: AccountSession = FakeAccountSession(),
        onSignIn: () -> Unit = {},
    ) = DefaultRootComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        accountManager = manager,
        authRepository = FakeAuthRepository(),
        accountSession = { session },
        onSignIn = onSignIn,
        today = today,
        timeZone = "UTC",
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun RootComponent.activeChild() = stack.value.active.instance

    @Test
    fun noActiveAccount_showsTheAuthShell() {
        assertTrue(root(FakeAccountManager()).activeChild() is RootComponent.Child.Auth)
    }

    @Test
    fun anActiveAccount_showsTheMainShell() {
        assertTrue(root(FakeAccountManager(active = account)).activeChild() is RootComponent.Child.Main)
    }

    @Test
    fun activatingAnAccount_swapsTheAuthShellForTheMainShell() {
        val manager = FakeAccountManager()
        val root = root(manager)
        assertTrue(root.activeChild() is RootComponent.Child.Auth)

        manager.signIn(account)

        assertTrue(root.activeChild() is RootComponent.Child.Main)
    }

    @Test
    fun signingOut_isReentrant_swapsTheMainShellBackForTheAuthShell() {
        val manager = FakeAccountManager(active = account)
        val root = root(manager)
        assertTrue(root.activeChild() is RootComponent.Child.Main)

        manager.signOut()

        assertTrue(root.activeChild() is RootComponent.Child.Auth)
    }

    @Test
    fun signOutThenSignIn_rebuildsTheMainShellFresh_perAccountIsolation() {
        val manager = FakeAccountManager(active = account)
        val root = root(manager)
        val firstMain = (root.activeChild() as RootComponent.Child.Main).component
        // Drill into Tasks on the first Main shell.
        firstMain.selectDestination(Destination.Tasks)
        (firstMain.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks)
            .component.list.onTaskClicked(TaskId("t-1"))

        manager.signOut()
        manager.signIn(account)

        // The Main shell is rebuilt per Account, not mutated across the boundary (ADR-0013 / ADR-0002):
        // a fresh instance, opened at the Plan home — the prior Account's drill-down is gone.
        val secondMain = (root.activeChild() as RootComponent.Child.Main).component
        assertNotSame(firstMain, secondMain)
        assertEquals(Destination.Plan, secondMain.stack.value.active.instance.destination)
    }

    @Test
    fun switchingAccount_rebuildsTheMainShellForTheNewAccount() {
        val personal = Account(AccountId("personal"), "Personal")
        val manager = FakeAccountManager(active = account).also { it.signIn(personal); it.activate(account.id) }
        val root = root(manager)
        val firstMain = (root.activeChild() as RootComponent.Child.Main).component

        (firstMain as DefaultMainShellComponent).switchAccount(personal.id)

        val secondMain = (root.activeChild() as RootComponent.Child.Main).component
        assertNotSame(firstMain, secondMain)
        assertEquals(personal, manager.activeAccount.value)
    }

    @Test
    fun authShellContinue_triggersOnSignIn() {
        val manager = FakeAccountManager()
        val root = root(manager, onSignIn = { manager.signIn(account) })

        (root.activeChild() as RootComponent.Child.Auth).component.onSignInClicked()

        assertTrue(root.activeChild() is RootComponent.Child.Main)
    }

    @Test
    fun backOnAuthShell_isNotConsumed() {
        assertFalse(root(FakeAccountManager()).onBackClicked())
    }

    @Test
    fun backOnMainShell_delegatesToTheActiveDestination() {
        val root = root(FakeAccountManager(active = account))
        val main = (root.activeChild() as RootComponent.Child.Main).component
        main.selectDestination(Destination.Tasks)
        (main.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks)
            .component.list.onTaskClicked(TaskId("t-1"))

        // Back dismisses the Tasks detail (delegated to the Main shell), so it is consumed.
        assertTrue(root.onBackClicked())
    }

    @Test
    fun addToPlanFromTasks_appliesThroughTheActiveAccountSession() {
        val session = FakeAccountSession()
        val root = root(FakeAccountManager(active = account), session = session)
        val main = (root.activeChild() as RootComponent.Child.Main).component
        main.selectDestination(Destination.Tasks)
        val tasks = (main.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks).component
        tasks.list.onTaskClicked(TaskId("t-1"))

        tasks.detail.value.child?.instance?.onAddToPlanClicked()

        assertEquals(listOf(TaskId("t-1")), session.addedToPlan)
    }

    @Test
    fun signingOutFromProfile_secureWipesTheActiveAccount_andReturnsToAuth() {
        val manager = FakeAccountManager(active = account)
        val root = root(manager)
        val main = (root.activeChild() as RootComponent.Child.Main).component
        main.selectDestination(Destination.Profile)
        val profile =
            (main.stack.value.active.instance as MainShellComponent.DestinationChild.Profile).component

        profile.onSignOut()

        // The only Account is removed (secure-wiped) → activeAccount null → swap back to the Auth shell.
        assertTrue(root.activeChild() is RootComponent.Child.Auth)
        assertNull(manager.activeAccount.value)
    }

    @Test
    fun signingOut_withAnotherAccountPresent_switchesToTheSibling_notAuth() {
        val personal = Account(AccountId("personal"), "Personal")
        val manager = FakeAccountManager(active = account).also { it.signIn(personal); it.activate(account.id) }
        val root = root(manager)
        val firstMain = (root.activeChild() as RootComponent.Child.Main).component
        firstMain.selectDestination(Destination.Profile)
        (firstMain.stack.value.active.instance as MainShellComponent.DestinationChild.Profile).component.onSignOut()

        // removeAccount re-points active to the remaining sibling → still Main, but the shell is REBUILT
        // per Account, not mutated across the boundary (ADR-0013 / ADR-0002): a fresh instance bound to
        // Personal, which we prove by reading the new Profile's bound Account.
        assertTrue(root.activeChild() is RootComponent.Child.Main)
        val secondMain = (root.activeChild() as RootComponent.Child.Main).component
        assertNotSame("the Main shell is re-keyed for the sibling, not mutated", firstMain, secondMain)
        secondMain.selectDestination(Destination.Profile)
        assertEquals(
            personal,
            (secondMain.stack.value.active.instance as MainShellComponent.DestinationChild.Profile)
                .component.account,
        )
        assertEquals(personal, manager.activeAccount.value)
    }
}

/**
 * In-memory [AccountManager] for the shell tests. State changes propagate synchronously to the
 * RootComponent's Unconfined collector, so the helpers ([signIn] / [signOut] / [activate]) drive the
 * shell without coroutine plumbing.
 */
private class FakeAccountManager(active: Account? = null) : AccountManager {
    private val _accounts = MutableStateFlow(listOfNotNull(active))
    override val accounts: StateFlow<List<Account>> = _accounts

    private val _activeAccount = MutableStateFlow(active)
    override val activeAccount: StateFlow<Account?> = _activeAccount

    override suspend fun addAccount(account: Account, token: String) = signIn(account)

    override suspend fun removeAccount(id: AccountId) {
        _accounts.value = _accounts.value.filterNot { it.id == id }
        if (_activeAccount.value?.id == id) _activeAccount.value = _accounts.value.firstOrNull()
    }

    override suspend fun switchTo(id: AccountId) = activate(id)

    override suspend fun load() = Unit

    fun signIn(account: Account) {
        if (_accounts.value.none { it.id == account.id }) _accounts.value = _accounts.value + account
        _activeAccount.value = account
    }

    fun activate(id: AccountId) {
        _activeAccount.value = _accounts.value.first { it.id == id }
    }

    fun signOut() {
        _activeAccount.value = null
    }
}

/** In-memory [AccountSession] over the in-memory demo repositories; records add-to-plan calls. */
private class FakeAccountSession(
    override val taskRepository: TaskRepository = DemoTaskRepository(SampleData.tasks),
    override val planRepository: PlanRepository = DemoPlanRepository(emptyList()),
    override val settingsRepository: SettingsRepository = FakeSettingsRepository(),
    override val settingsWriter: SettingsWriter = FakeSettingsWriter(),
) : AccountSession {
    val addedToPlan = mutableListOf<TaskId>()
    val workingStateSets = mutableListOf<Pair<TaskId, com.circuitstitch.deferno.core.model.WorkingState>>()

    override suspend fun addToPlan(taskId: TaskId, date: LocalDate, tz: String) {
        addedToPlan += taskId
    }

    override val workingStateEditor: com.circuitstitch.deferno.feature.tasks.WorkingStateEditor =
        com.circuitstitch.deferno.feature.tasks.WorkingStateEditor { id, target, _ ->
            workingStateSets += id to target
        }
}
