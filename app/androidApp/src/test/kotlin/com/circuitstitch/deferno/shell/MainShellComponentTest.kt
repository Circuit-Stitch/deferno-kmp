package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.ui.FakeAuthRepository
import com.circuitstitch.deferno.ui.sampleAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Main shell's Destination graph (ADR-0013 / ADR-0007 tier 1): a registry the nav suite renders,
 * lateral **state-preserving** switching (multiple back stacks), the cross-feature routing it owns,
 * and shell back-handling. Decompose navigation is synchronous, so these assert without coroutine
 * plumbing (the feature flows are lazy `WhileSubscribed`, so navigation never depends on them).
 */
class MainShellComponentTest {

    private val today = LocalDate(2026, 6, 6)

    private fun shell(
        taskRepo: DemoTaskRepository = DemoTaskRepository(SampleData.tasks),
        planRepo: DemoPlanRepository = DemoPlanRepository(emptyList()),
        auth: AuthRepository = FakeAuthRepository(),
        account: Account = sampleAccount,
        output: (MainShellComponent.Output) -> Unit = {},
    ) = DefaultMainShellComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        taskRepository = taskRepo,
        planRepository = planRepo,
        authRepository = auth,
        account = account,
        today = today,
        timeZone = "UTC",
        output = output,
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun MainShellComponent.activeDestination(): Destination =
        stack.value.active.instance.destination

    private fun MainShellComponent.tasks(): com.circuitstitch.deferno.feature.tasks.TasksComponent =
        (stack.value.active.instance as MainShellComponent.DestinationChild.Tasks).component

    private fun MainShellComponent.profile(): ProfileComponent =
        (stack.value.active.instance as MainShellComponent.DestinationChild.Profile).component

    @Test
    fun opensIntoThePlan() {
        assertEquals(Destination.Plan, shell().activeDestination())
    }

    @Test
    fun registryListsEveryDestination_notAHardcodedCount() {
        // The nav suite renders this; it must be the whole ordered registry, not a fixed two-way switch.
        assertEquals(Destination.entries, shell().destinations)
    }

    @Test
    fun selectingTasks_switchesActiveDestinationLaterally() {
        val shell = shell()
        shell.selectDestination(Destination.Tasks)
        assertEquals(Destination.Tasks, shell.activeDestination())
    }

    @Test
    fun lateralSwitch_preservesEachDestinationsState() {
        val shell = shell()

        // Drill into Tasks: open a Task's detail (its private tier-3 state).
        shell.selectDestination(Destination.Tasks)
        val tasks = shell.tasks()
        tasks.list.onTaskClicked(TaskId("t-1"))
        assertEquals(TaskId("t-1"), tasks.detail.value.child?.instance?.taskId)

        // Leave Tasks for Plan, then come back — the ADR-0007 tier-1 acceptance scenario.
        shell.selectDestination(Destination.Plan)
        assertEquals(Destination.Plan, shell.activeDestination())
        shell.selectDestination(Destination.Tasks)

        // Same Tasks instance, detail still open on the same Task — multiple back stacks, no reset.
        val tasksAgain = shell.tasks()
        assertSame("the Tasks Destination is retained across the switch", tasks, tasksAgain)
        assertEquals(TaskId("t-1"), tasksAgain.detail.value.child?.instance?.taskId)
    }

    @Test
    fun repeatedLateralSwitches_preserveEachDestinationsState() {
        val shell = shell()
        shell.selectDestination(Destination.Tasks)
        val tasks = shell.tasks()
        tasks.list.onTaskClicked(TaskId("t-1"))
        assertEquals(TaskId("t-1"), tasks.detail.value.child?.instance?.taskId)

        // Not just one round-trip: the Tasks Destination must stay the same instance with its detail
        // intact across many lateral switches (multiple back stacks survive rapid nav — ADR-0007 t1).
        repeat(3) {
            shell.selectDestination(Destination.Plan)
            assertEquals(Destination.Plan, shell.activeDestination())
            shell.selectDestination(Destination.Tasks)
            assertSame(tasks, shell.tasks())
            assertEquals(TaskId("t-1"), shell.tasks().detail.value.child?.instance?.taskId)
        }
    }

    @Test
    fun planTap_opensThatTaskInTheTasksDestination() {
        val shell = shell()
        val plan = (stackPlan(shell)).component
        plan.onTaskClicked(TaskId("t-1"))

        assertEquals(Destination.Tasks, shell.activeDestination())
        assertEquals(TaskId("t-1"), shell.tasks().detail.value.child?.instance?.taskId)
    }

    @Test
    fun addToPlanIntent_bubblesToOutput() {
        val outputs = mutableListOf<MainShellComponent.Output>()
        val shell = shell(output = outputs::add)
        shell.selectDestination(Destination.Tasks)
        val tasks = shell.tasks()
        tasks.list.onTaskClicked(TaskId("t-1"))

        tasks.detail.value.child?.instance?.onAddToPlanClicked()

        assertEquals(
            listOf<MainShellComponent.Output>(MainShellComponent.Output.AddToPlanRequested(TaskId("t-1"))),
            outputs,
        )
    }

    @Test
    fun back_dismissesActivePane_thenReturnsToTheHome_thenIsNotConsumed() {
        val shell = shell()
        shell.selectDestination(Destination.Tasks)
        shell.tasks().list.onTaskClicked(TaskId("t-1")) // detail open on the Tasks Destination

        assertTrue("dismisses the open detail", shell.onBack())
        assertNull(shell.tasks().detail.value.child)
        assertEquals(Destination.Tasks, shell.activeDestination())

        assertTrue("no pane left → return to the Plan home", shell.onBack())
        assertEquals(Destination.Plan, shell.activeDestination())

        assertFalse("on the Plan home with nothing to dismiss → not consumed", shell.onBack())
    }

    @Test
    fun back_dismissesForegroundedPaneAfterDrillingIntoATreeChild() {
        val shell = shell()
        shell.selectDestination(Destination.Tasks)
        val tasks = shell.tasks()
        tasks.list.onTaskClicked(TaskId("t-1"))                 // detail of t-1 (a task with children)
        tasks.detail.value.child?.instance?.onShowTreeClicked() // open its breakdown (tree)
        tasks.tree.value.child?.instance?.onChildClicked(TaskId("t-1a")) // drill into a child

        // Foreground is the child's detail, with the tree still co-resident behind it.
        assertEquals(TaskId("t-1a"), tasks.detail.value.child?.instance?.taskId)
        assertNotNull("tree stays open beneath the child detail", tasks.tree.value.child)

        assertTrue("back closes the foregrounded child detail", shell.onBack())
        assertNull("child detail dismissed", tasks.detail.value.child)
        assertNotNull("tree now revealed", tasks.tree.value.child)

        assertTrue("back closes the tree", shell.onBack())
        assertNull(tasks.tree.value.child)
        assertEquals(Destination.Tasks, shell.activeDestination())
    }

    // --- v1 Destination set (#70, ADR-0015) ---

    @Test
    fun registryIsTheV1DestinationSet_inNavOrder() {
        assertEquals(
            listOf(
                Destination.Plan,
                Destination.Calendar,
                Destination.Tasks,
                Destination.Profile,
                Destination.Settings,
            ),
            shell().destinations,
        )
    }

    @Test
    fun selectingProfile_switchesLaterally_andRetainsItAcrossASwitch() {
        val shell = shell()
        shell.selectDestination(Destination.Profile)
        assertEquals(Destination.Profile, shell.activeDestination())

        val profile = shell.profile()
        shell.selectDestination(Destination.Plan)
        shell.selectDestination(Destination.Profile)

        assertSame("the Profile Destination is retained across a lateral switch", profile, shell.profile())
    }

    @Test
    fun calendarAndSettings_openAsReservedPlaceholders() {
        val shell = shell()

        shell.selectDestination(Destination.Calendar)
        val calendar = shell.stack.value.active.instance
        assertTrue(calendar is MainShellComponent.DestinationChild.Placeholder)
        assertEquals(Destination.Calendar, calendar.destination)

        shell.selectDestination(Destination.Settings)
        val settings = shell.stack.value.active.instance
        assertTrue(settings is MainShellComponent.DestinationChild.Placeholder)
        assertEquals(Destination.Settings, settings.destination)
    }

    @Test
    fun profile_isBuiltForTheBoundActiveAccount() {
        val shell = shell()
        shell.selectDestination(Destination.Profile)
        assertEquals(sampleAccount, shell.profile().account)
    }

    @Test
    fun profileSignOut_bubblesToOutputForTheHost() {
        val outputs = mutableListOf<MainShellComponent.Output>()
        val shell = shell(output = outputs::add)
        shell.selectDestination(Destination.Profile)

        shell.profile().onSignOut()

        assertEquals(
            listOf<MainShellComponent.Output>(MainShellComponent.Output.SignOutRequested),
            outputs,
        )
    }

    // --- Shell-level overlay route (#70, ADR-0015) ---

    @Test
    fun overlay_opensAboveTheForegroundDestination_andDismissesBackToOrigin() {
        val shell = shell()
        assertNull("no overlay initially", shell.overlay.value.child)

        shell.openOverlay(OverlayRoute.Placeholder)
        assertNotNull("overlay pushed above the foreground Destination", shell.overlay.value.child)
        assertEquals("the foreground Destination is untouched", Destination.Plan, shell.activeDestination())

        shell.dismissOverlay()
        assertNull("overlay dismissed back to origin", shell.overlay.value.child)
    }

    @Test
    fun back_dismissesTheOverlayBeforeTheActiveDestinationsInnerState() {
        val shell = shell()
        shell.selectDestination(Destination.Tasks)
        shell.tasks().list.onTaskClicked(TaskId("t-1")) // a dismissable inner pane on Tasks
        shell.openOverlay(OverlayRoute.Placeholder)

        // Back hits the overlay first — the Tasks detail stays open beneath it.
        assertTrue("back dismisses the overlay", shell.onBack())
        assertNull(shell.overlay.value.child)
        assertNotNull("the Tasks detail is untouched beneath the overlay", shell.tasks().detail.value.child)

        // Then back resumes the normal precedence: dismiss the Tasks detail.
        assertTrue("back now dismisses the Tasks detail", shell.onBack())
        assertNull(shell.tasks().detail.value.child)
    }

    private fun stackPlan(shell: MainShellComponent): MainShellComponent.DestinationChild.Plan =
        shell.stack.value.active.instance as MainShellComponent.DestinationChild.Plan
}
