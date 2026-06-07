package com.circuitstitch.deferno.desktop.shell

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.desktop.demo.DemoPlanRepository
import com.circuitstitch.deferno.desktop.demo.DemoTaskRepository
import com.circuitstitch.deferno.desktop.demo.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-scene navigation root (ADR-0013), desktop edition: the two-state Shell selected by auth
 * state, re-entrant in both directions, with shell-back (Esc) routed to the active Shell and the Main
 * shell's add-to-plan intent forwarded to the host. Mirrors app/androidApp's RootComponentTest.
 */
class RootComponentTest {

    private val today = LocalDate(2026, 6, 6)

    private fun root(
        gate: AuthGate,
        output: (RootComponent.Output) -> Unit = {},
    ) = DefaultRootComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        authGate = gate,
        taskRepository = DemoTaskRepository(SampleData.tasks),
        planRepository = DemoPlanRepository(emptyList()),
        today = today,
        timeZone = "UTC",
        output = output,
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun RootComponent.activeChild() = stack.value.active.instance

    @Test
    fun signedOutGate_showsTheAuthShell() {
        assertTrue(root(StubAuthGate(initiallySignedIn = false)).activeChild() is RootComponent.Child.Auth)
    }

    @Test
    fun signedInGate_showsTheMainShell() {
        assertTrue(root(StubAuthGate(initiallySignedIn = true)).activeChild() is RootComponent.Child.Main)
    }

    @Test
    fun signingIn_swapsTheAuthShellForTheMainShell() {
        val gate = StubAuthGate(initiallySignedIn = false)
        val root = root(gate)
        assertTrue(root.activeChild() is RootComponent.Child.Auth)

        gate.signIn()

        assertTrue(root.activeChild() is RootComponent.Child.Main)
    }

    @Test
    fun signingOut_isReentrant_swapsTheMainShellBackForTheAuthShell() {
        val gate = StubAuthGate(initiallySignedIn = true)
        val root = root(gate)
        assertTrue(root.activeChild() is RootComponent.Child.Main)

        gate.signOut()

        assertTrue(root.activeChild() is RootComponent.Child.Auth)
    }

    @Test
    fun signOutThenSignIn_rebuildsTheMainShellFresh_perAccountIsolation() {
        val gate = StubAuthGate(initiallySignedIn = true)
        val root = root(gate)
        val firstMain = (root.activeChild() as RootComponent.Child.Main).component
        // Drill into Tasks on the first Main shell.
        firstMain.selectDestination(Destination.Tasks)
        (firstMain.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks)
            .component.list.onTaskClicked(TaskId("t-1"))

        gate.signOut()
        gate.signIn()

        // The Main shell is rebuilt per Account, not mutated across the boundary (ADR-0013 / ADR-0002):
        // a fresh instance, opened at the Plan home — the prior Account's drill-down is gone.
        val secondMain = (root.activeChild() as RootComponent.Child.Main).component
        assertNotSame(firstMain, secondMain)
        assertEquals(Destination.Plan, secondMain.stack.value.active.instance.destination)
    }

    @Test
    fun authShellContinue_completesSignIn() {
        val root = root(StubAuthGate(initiallySignedIn = false))

        (root.activeChild() as RootComponent.Child.Auth).component.onSignInClicked()

        assertTrue(root.activeChild() is RootComponent.Child.Main)
    }

    @Test
    fun backOnAuthShell_isNotConsumed() {
        assertFalse(root(StubAuthGate(initiallySignedIn = false)).onBackClicked())
    }

    @Test
    fun backOnMainShell_delegatesToTheActiveDestination() {
        val root = root(StubAuthGate(initiallySignedIn = true))
        val main = (root.activeChild() as RootComponent.Child.Main).component
        main.selectDestination(Destination.Tasks)
        main.stack.value.active.instance.let { child ->
            (child as MainShellComponent.DestinationChild.Tasks).component.list.onTaskClicked(TaskId("t-1"))
        }

        // Esc dismisses the Tasks detail (delegated to the Main shell), so it is consumed.
        assertTrue(root.onBackClicked())
    }

    @Test
    fun addToPlanFromTasks_bubblesUpToRootOutput() {
        val outputs = mutableListOf<RootComponent.Output>()
        val root = root(StubAuthGate(initiallySignedIn = true), output = outputs::add)
        val main = (root.activeChild() as RootComponent.Child.Main).component
        main.selectDestination(Destination.Tasks)
        val tasks = (main.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks).component
        tasks.list.onTaskClicked(TaskId("t-1"))

        tasks.detail.value.child?.instance?.onAddToPlanClicked()

        assertEquals(
            listOf<RootComponent.Output>(RootComponent.Output.AddToPlanRequested(TaskId("t-1"))),
            outputs,
        )
    }
}
