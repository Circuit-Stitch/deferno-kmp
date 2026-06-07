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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Main shell's Destination graph (ADR-0013 / ADR-0007 tier 1), desktop edition: a registry the nav
 * suite renders, lateral **state-preserving** switching (multiple back stacks), the cross-feature
 * routing it owns, and shell back-handling. Decompose navigation is synchronous, so these assert
 * without coroutine plumbing. Mirrors app/androidApp's MainShellComponentTest.
 */
class MainShellComponentTest {

    private val today = LocalDate(2026, 6, 6)

    private fun shell(
        taskRepo: DemoTaskRepository = DemoTaskRepository(SampleData.tasks),
        planRepo: DemoPlanRepository = DemoPlanRepository(emptyList()),
        output: (MainShellComponent.Output) -> Unit = {},
    ) = DefaultMainShellComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        taskRepository = taskRepo,
        planRepository = planRepo,
        today = today,
        timeZone = "UTC",
        output = output,
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun MainShellComponent.activeDestination(): Destination =
        stack.value.active.instance.destination

    private fun MainShellComponent.tasks(): com.circuitstitch.deferno.feature.tasks.TasksComponent =
        (stack.value.active.instance as MainShellComponent.DestinationChild.Tasks).component

    @Test
    fun opensIntoThePlan() {
        assertEquals(Destination.Plan, shell().activeDestination())
    }

    @Test
    fun registryListsEveryDestination_notAHardcodedCount() {
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
        val plan = stackPlan(shell).component
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

    private fun stackPlan(shell: MainShellComponent): MainShellComponent.DestinationChild.Plan =
        shell.stack.value.active.instance as MainShellComponent.DestinationChild.Plan
}
