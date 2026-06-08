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
import com.circuitstitch.deferno.feature.settings.SettingsCategory
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.ui.FakeAuthRepository
import com.circuitstitch.deferno.ui.FakeSettingsRepository
import com.circuitstitch.deferno.ui.FakeSettingsWriter
import com.circuitstitch.deferno.ui.sampleAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        settingsRepo: FakeSettingsRepository = FakeSettingsRepository(),
        settingsWriter: FakeSettingsWriter = FakeSettingsWriter(settingsRepo),
        output: (MainShellComponent.Output) -> Unit = {},
        create: suspend (com.circuitstitch.deferno.core.domain.command.CreateItem.Payload) -> com.circuitstitch.deferno.core.domain.command.CommandResult =
            { com.circuitstitch.deferno.core.domain.command.CommandResult.Offline(com.circuitstitch.deferno.core.domain.command.CommandKind.CreateItem) },
    ) = DefaultMainShellComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        taskRepository = taskRepo,
        planRepository = planRepo,
        authRepository = auth,
        settingsRepository = settingsRepo,
        settingsWriter = settingsWriter,
        account = account,
        today = today,
        timeZone = "UTC",
        output = output,
        coroutineContext = Dispatchers.Unconfined,
        create = create,
    )

    private fun MainShellComponent.settings(): SettingsComponent =
        (stack.value.active.instance as MainShellComponent.DestinationChild.Settings).component

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
        assertSame(tasks, tasksAgain, "the Tasks Destination is retained across the switch")
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

        assertTrue(shell.onBack(), "dismisses the open detail")
        assertNull(shell.tasks().detail.value.child)
        assertEquals(Destination.Tasks, shell.activeDestination())

        assertTrue(shell.onBack(), "no pane left → return to the Plan home")
        assertEquals(Destination.Plan, shell.activeDestination())

        assertFalse(shell.onBack(), "on the Plan home with nothing to dismiss → not consumed")
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
        assertNotNull(tasks.tree.value.child, "tree stays open beneath the child detail")

        assertTrue(shell.onBack(), "back closes the foregrounded child detail")
        assertNull(tasks.detail.value.child, "child detail dismissed")
        assertNotNull(tasks.tree.value.child, "tree now revealed")

        assertTrue(shell.onBack(), "back closes the tree")
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

        assertSame(profile, shell.profile(), "the Profile Destination is retained across a lateral switch")
    }

    @Test
    fun calendar_opensAsAReservedPlaceholder() {
        val shell = shell()

        shell.selectDestination(Destination.Calendar)
        val calendar = shell.stack.value.active.instance
        assertTrue(calendar is MainShellComponent.DestinationChild.Placeholder)
        assertEquals(Destination.Calendar, calendar.destination)
    }

    @Test
    fun settings_opensAsARealTier3Destination() {
        // Settings is no longer a placeholder (#72): it opens the real Settings drill-down at its list.
        val shell = shell()

        shell.selectDestination(Destination.Settings)
        val settings = shell.stack.value.active.instance
        assertTrue(settings is MainShellComponent.DestinationChild.Settings)
        assertEquals(Destination.Settings, settings.destination)
        assertEquals(
            SettingsComponent.SettingsChild.List,
            settings.component.stack.value.active.instance, // smart-cast via the assertTrue above
        )
    }

    @Test
    fun settings_isRetainedAcrossALateralSwitch() {
        val shell = shell()
        shell.selectDestination(Destination.Settings)
        val settings = shell.settings()
        // Drill into a category (its tier-3 state).
        settings.openCategory(SettingsCategory.Appearance)

        shell.selectDestination(Destination.Plan)
        shell.selectDestination(Destination.Settings)

        // Same Settings instance, still drilled into Appearance — multiple back stacks, no reset.
        assertSame(settings, shell.settings(), "the Settings Destination is retained across the switch")
        assertTrue(shell.settings().stack.value.active.instance is SettingsComponent.SettingsChild.Detail)
    }

    @Test
    fun back_delegatesToTheSettingsTier3StackBeforeFallingBackToPlan() {
        val shell = shell()
        shell.selectDestination(Destination.Settings)
        shell.settings().openCategory(SettingsCategory.Appearance) // a tier-3 detail open

        assertTrue(shell.onBack(), "back pops the open Settings category detail")
        assertEquals(
            SettingsComponent.SettingsChild.List,
            shell.settings().stack.value.active.instance,
        )
        assertEquals(Destination.Settings, shell.activeDestination())

        assertTrue(shell.onBack(), "nothing left inside Settings → return to the Plan home")
        assertEquals(Destination.Plan, shell.activeDestination())
    }

    @Test
    fun settingsOpenProfile_switchesLaterallyToProfile() {
        val shell = shell()
        shell.selectDestination(Destination.Settings)

        shell.settings().onOpenProfile()

        assertEquals(Destination.Profile, shell.activeDestination())
    }

    @Test
    fun settingsAppPermissions_bubblesOpenOsAppSettingsToTheHost() {
        val outputs = mutableListOf<MainShellComponent.Output>()
        val shell = shell(output = outputs::add)
        shell.selectDestination(Destination.Settings)

        shell.settings().onOpenAppPermissions()

        assertEquals(
            listOf<MainShellComponent.Output>(MainShellComponent.Output.OpenOsAppSettings),
            outputs,
        )
    }

    @Test
    fun settingsDataExportImport_bubblesOpenDataExportImportToTheHost() {
        // AC #3: the reachable export/import web action routes up to the host (which deep-links the web app).
        val outputs = mutableListOf<MainShellComponent.Output>()
        val shell = shell(output = outputs::add)
        shell.selectDestination(Destination.Settings)

        shell.settings().onOpenDataExportImport()

        assertEquals(
            listOf<MainShellComponent.Output>(MainShellComponent.Output.OpenDataExportImport),
            outputs,
        )
    }

    @Test
    fun settingsSubmitFeedback_bubblesOpenSubmitFeedbackToTheHost() {
        // AC #4: the reachable submit-feedback web action routes up to the host.
        val outputs = mutableListOf<MainShellComponent.Output>()
        val shell = shell(output = outputs::add)
        shell.selectDestination(Destination.Settings)

        shell.settings().onOpenSubmitFeedback()

        assertEquals(
            listOf<MainShellComponent.Output>(MainShellComponent.Output.OpenSubmitFeedback),
            outputs,
        )
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

    @Test
    fun chromeSignOut_bubblesToOutputForTheHost() {
        // The shell-chrome sign-out affordance (the desktop Account menu, ADR-0017) raises the same
        // SignOutRequested intent as the Profile button, from outside the Destination graph.
        val outputs = mutableListOf<MainShellComponent.Output>()
        val shell = shell(output = outputs::add)

        shell.signOut()

        assertEquals(
            listOf<MainShellComponent.Output>(MainShellComponent.Output.SignOutRequested),
            outputs,
        )
    }

    // --- Shell-level overlay route (#70, ADR-0015) ---

    @Test
    fun overlay_opensAboveTheForegroundDestination_andDismissesBackToOrigin() {
        val shell = shell()
        assertNull(shell.overlay.value.child, "no overlay initially")

        shell.openOverlay(OverlayRoute.Placeholder)
        assertNotNull(shell.overlay.value.child, "overlay pushed above the foreground Destination")
        assertEquals(Destination.Plan, shell.activeDestination(), "the foreground Destination is untouched")

        shell.dismissOverlay()
        assertNull(shell.overlay.value.child, "overlay dismissed back to origin")
    }

    @Test
    fun back_dismissesTheOverlayBeforeTheActiveDestinationsInnerState() {
        val shell = shell()
        shell.selectDestination(Destination.Tasks)
        shell.tasks().list.onTaskClicked(TaskId("t-1")) // a dismissable inner pane on Tasks
        shell.openOverlay(OverlayRoute.Placeholder)

        // Back hits the overlay first — the Tasks detail stays open beneath it.
        assertTrue(shell.onBack(), "back dismisses the overlay")
        assertNull(shell.overlay.value.child)
        assertNotNull(shell.tasks().detail.value.child, "the Tasks detail is untouched beneath the overlay")

        // Then back resumes the normal precedence: dismiss the Tasks detail.
        assertTrue(shell.onBack(), "back now dismisses the Tasks detail")
        assertNull(shell.tasks().detail.value.child)
    }

    // --- Global Search overlay route (#73, ADR-0015) ---

    @Test
    fun search_opensAboveTheForegroundDestination_andDismissesBackToOrigin() {
        val shell = shell()
        assertNull(shell.overlay.value.child, "no overlay initially")

        shell.openOverlay(OverlayRoute.Search)
        val child = shell.overlay.value.child?.instance
        assertTrue(child is MainShellComponent.OverlayChild.Search, "the Search overlay is pushed above the foreground")
        assertEquals(Destination.Plan, shell.activeDestination(), "the foreground Destination is untouched")

        shell.dismissOverlay()
        assertNull(shell.overlay.value.child, "overlay dismissed back to origin")
    }

    // --- New overlay route (#71, ADR-0015/0016) ---

    @Test
    fun newOverlay_opensAboveTheForegroundDestination_andDismissesBackToOrigin() {
        val shell = shell()
        assertNull(shell.overlay.value.child, "no overlay initially")

        shell.openOverlay(OverlayRoute.New)
        val child = shell.overlay.value.child?.instance
        assertTrue(child is MainShellComponent.OverlayChild.New, "the New create surface is pushed")
        assertEquals(Destination.Plan, shell.activeDestination(), "the foreground Destination is untouched")

        shell.dismissOverlay()
        assertNull(shell.overlay.value.child, "overlay dismissed back to origin")
    }

    @Test
    fun searchResultTap_opensThatTaskInTheTasksDestination_andDismissesTheOverlay() {
        val shell = shell()
        shell.openOverlay(OverlayRoute.Search)
        val search = (shell.overlay.value.child?.instance as MainShellComponent.OverlayChild.Search).component

        search.onResultClicked(TaskId("t-1"))

        // Mirrors the Plan-tap routing: switch to Tasks, open the Task, and pop the overlay.
        assertNull(shell.overlay.value.child, "the overlay is dismissed on result tap")
        assertEquals(Destination.Tasks, shell.activeDestination())
        assertEquals(TaskId("t-1"), shell.tasks().detail.value.child?.instance?.taskId)
    }

    @Test
    fun back_dismissesTheSearchOverlayBeforeTheActiveDestinationsInnerState() {
        val shell = shell()
        shell.selectDestination(Destination.Tasks)
        shell.tasks().list.onTaskClicked(TaskId("t-1"))
        shell.openOverlay(OverlayRoute.Search)

        assertTrue(shell.onBack(), "back dismisses the Search overlay")
        assertNull(shell.overlay.value.child)
        assertNotNull(shell.tasks().detail.value.child, "the Tasks detail is untouched beneath the overlay")

        assertTrue(shell.onBack(), "back now dismisses the Tasks detail")
        assertNull(shell.tasks().detail.value.child)
    }

    @Test
    fun back_dismissesTheNewOverlayFirst() {
        val shell = shell()
        shell.selectDestination(Destination.Tasks)
        shell.openOverlay(OverlayRoute.New)

        assertTrue(shell.onBack(), "back dismisses the New overlay")
        assertNull(shell.overlay.value.child)
        // The Tasks Destination is untouched beneath it (still foreground).
        assertEquals(Destination.Tasks, shell.activeDestination())
    }

    @Test
    fun newOverlay_pickerDefaultsToTask_andSubmitDispatchesTheCreateCommand() {
        val created = mutableListOf<com.circuitstitch.deferno.core.domain.command.CreateItem.Payload>()
        val shell = shell(
            create = { payload ->
                created += payload
                com.circuitstitch.deferno.core.domain.command.CommandResult.Accepted(
                    com.circuitstitch.deferno.core.domain.command.CommandKind.CreateItem,
                )
            },
        )
        shell.openOverlay(OverlayRoute.New)
        val newComponent = (shell.overlay.value.child!!.instance as MainShellComponent.OverlayChild.New).component

        // The picker defaults to Task (ADR-0015 — explicit, sensible default), and the form adapts.
        assertEquals(com.circuitstitch.deferno.core.model.ItemKind.Task, newComponent.state.value.selectedKind)

        newComponent.setTitle("buy milk")
        newComponent.submit() // Unconfined → runs synchronously

        // The create command was dispatched with a Task payload; an Accepted result dismisses the overlay.
        assertEquals(1, created.size)
        assertTrue(created[0] is com.circuitstitch.deferno.core.domain.command.CreateItem.Payload.Task)
        assertNull(shell.overlay.value.child, "an accepted create dismisses the overlay")
    }

    @Test
    fun newOverlay_offlineShowsReconnectToSaveAndStaysOpen() {
        val shell = shell() // default create returns Offline
        shell.openOverlay(OverlayRoute.New)
        val newComponent = (shell.overlay.value.child!!.instance as MainShellComponent.OverlayChild.New).component

        newComponent.selectKind(com.circuitstitch.deferno.core.model.ItemKind.Habit)
        newComponent.setTitle("stretch")
        newComponent.submit()

        // Offline → gentle "reconnect to save" status, overlay stays open, nothing dismissed.
        assertEquals(NewStatus.Offline, newComponent.state.value.status)
        assertNotNull(shell.overlay.value.child, "the New overlay stays open while offline")
    }

    private fun stackPlan(shell: MainShellComponent): MainShellComponent.DestinationChild.Plan =
        shell.stack.value.active.instance as MainShellComponent.DestinationChild.Plan
}
