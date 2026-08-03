package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.demo.DemoItemRepository
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.ui.FakeAuthRepository
import com.circuitstitch.deferno.ui.FakeSettingsEditor
import com.circuitstitch.deferno.ui.FakeSettingsRepository
import com.circuitstitch.deferno.ui.sampleAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The kind-neutral **"in today"** join (#386) — the answer to "is this on my plan today?" that the Item
 * tree's first filter segment narrows on. Before this the segment consulted no date, no plan and no
 * calendar: it was a synonym for "Active" on Apple and for **"All"** on Compose, so it silently corrupted
 * every bug report about the plan.
 *
 * Two halves are asserted here because both can fail silently:
 *  1. [inTodayIds] itself — the union, the `seriesId` (not `taskId`) key, and the deliberate exclusions.
 *  2. The shell wiring — that the join actually reaches [com.circuitstitch.deferno.feature.tasks.ItemTreeState],
 *     and that the shell **warms today's calendar window**. Without that warm-up the recurring arm reads a
 *     stale cache and "In today" hides every Habit/Chore/Event — worse than the decorative segment it replaces.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drive the WhileSubscribed tree state
class InTodayJoinTest {

    private val today = LocalDate(2026, 6, 6)
    private val epoch = Instant.fromEpochSeconds(1_780_000_000)

    private fun task(id: String) = Task(
        id = TaskId(id),
        orgSlug = "u-deferno",
        title = id,
        workingState = WorkingState.Open,
        dateCreated = epoch,
        hydration = HydrationState.Summary,
    )

    /** A recurring firing: a row that belongs to a series ([seriesId]) — the definition id a tree row carries. */
    private fun firing(seriesId: String?, taskId: String, kind: ItemKind? = ItemKind.Habit) = CalendarItem(
        id = "feed-$taskId-$today",
        taskId = taskId,
        seriesId = seriesId,
        title = taskId,
        date = today,
        start = epoch,
        end = epoch,
        allDay = true,
        status = WorkingState.Open,
        kind = kind,
        source = CalendarSource.Deferno,
    )

    // --- the join itself ---

    @Test
    fun unionsTodaysPlanTasksWithTodaysRecurringFirings() {
        val ids = inTodayIds(
            plan = listOf(task("t-1"), task("t-2")),
            day = listOf(firing(seriesId = "h-morning-run", taskId = "occ-1")),
        )

        assertEquals(setOf("t-1", "t-2", "h-morning-run"), ids)
    }

    /**
     * The one thing this join must not get wrong. `seriesId` is the recurring **definition's** id, which
     * is exactly what an Item tree row is keyed by; `taskId` is the id the *occurrence endpoints* address
     * (#380). Keying on `taskId` would produce a set that matches no tree row at all — an "In today" that
     * is empty for every recurring item, which reads as a confident "no".
     */
    @Test
    fun keysTheRecurringArmOnTheSeriesDefinitionIdNotTheOccurrenceTargetId() {
        val ids = inTodayIds(plan = emptyList(), day = listOf(firing(seriesId = "h-def", taskId = "occ-head")))

        assertEquals(setOf("h-def"), ids)
        assertTrue("occ-head" !in ids, "the occurrence-endpoint id is NOT a tree row id")
    }

    @Test
    fun dropsAMerelyDatedRowThatBelongsToNoSeries() {
        // A one-off dated Task in the feed (seriesId null): in today because it is on the plan, never
        // because it merely carries a date — the reference client keeps those gates separate too.
        val ids = inTodayIds(plan = emptyList(), day = listOf(firing(seriesId = null, taskId = "t-dated", kind = null)))

        assertEquals(emptySet(), ids)
    }

    @Test
    fun collapsesAnIdPresentOnBothSidesToOneEntry() {
        val ids = inTodayIds(plan = listOf(task("shared")), day = listOf(firing(seriesId = "shared", taskId = "occ")))

        assertEquals(setOf("shared"), ids)
    }

    @Test
    fun emptyOnBothSidesIsAnEmptySetNotEverything() {
        assertEquals(emptySet(), inTodayIds(plan = emptyList(), day = emptyList()))
    }

    // --- the shell wiring ---

    @Test
    fun theJoinedSetReachesTheItemTreeStateForBothKinds() = runTest {
        val calendar = RecordingCalendarRepository(day = listOf(firing(seriesId = "h-run", taskId = "occ-1")))
        val shell = shell(plan = listOf(task("t-1")), calendar = calendar)
        shell.selectDestination(Destination.Tasks)
        val tree = (shell.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks).component.tree
        backgroundScope.launch { tree.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            setOf("t-1", "h-run"),
            tree.state.value.inTodayIds,
            "a planned Task AND a Habit firing today both reach the tree",
        )
    }

    /**
     * The trap the audit found: `refreshWindow` has exactly one *other* production caller — the Calendar
     * Destination's own init — so without this the recurring arm reads whatever stale span the DB holds
     * for a user who never opens the Calendar tab.
     */
    @Test
    fun theShellWarmsTodaysCalendarWindowSoTheRecurringArmIsNotReadFromAStaleCache() = runTest {
        val calendar = RecordingCalendarRepository()
        shell(calendar = calendar)
        advanceUntilIdle()

        assertEquals(listOf(Triple(today, LocalDate(2026, 6, 7), "UTC")), calendar.refreshed, "one one-day warm-up")
    }

    /** A failing pull must never take the shell down with it — the cached window simply stays (ADR-0001). */
    @Test
    fun aThrowingWarmUpIsSwallowedAndTheShellStillBuilds() = runTest {
        val calendar = RecordingCalendarRepository(refreshThrows = true)
        val shell = shell(calendar = calendar)
        advanceUntilIdle()

        assertEquals(1, calendar.refreshed.size, "the warm-up was attempted")
        assertEquals(Destination.Plan, shell.stack.value.active.instance.destination, "and the shell is alive")
    }

    private class RecordingCalendarRepository(
        private val day: List<CalendarItem> = emptyList(),
        private val refreshThrows: Boolean = false,
    ) : CalendarRepository {
        val refreshed = mutableListOf<Triple<LocalDate, LocalDate, String>>()

        override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> =
            MutableStateFlow(emptyMap())

        override fun observeDay(date: LocalDate): Flow<List<CalendarItem>> = MutableStateFlow(day)

        override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {
            refreshed += Triple(from, to, tz)
            if (refreshThrows) error("feed unreachable")
        }

        override suspend fun reconcile() {}
    }

    private fun kotlinx.coroutines.test.TestScope.shell(
        plan: List<Task> = emptyList(),
        calendar: CalendarRepository = RecordingCalendarRepository(),
    ) = DefaultMainShellComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        itemRepository = DemoItemRepository(
            listOf(
                Item(id = "t-1", kind = ItemKind.Task, title = "Plan the launch", sequence = 0),
                Item(id = "h-run", kind = ItemKind.Habit, title = "Morning run", sequence = 1),
            ),
        ),
        foldStore = InMemoryItemFoldStore(),
        taskRepository = DemoTaskRepository(emptyList()),
        planRepository = DemoPlanRepository(plan),
        authRepository = FakeAuthRepository(),
        settingsRepository = FakeSettingsRepository(),
        settingsEditor = FakeSettingsEditor(),
        account = sampleAccount,
        today = today,
        timeZone = "UTC",
        calendarRepository = calendar,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )
}
