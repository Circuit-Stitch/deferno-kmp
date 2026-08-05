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
import com.circuitstitch.deferno.core.model.PlanRow
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
 *  1. [inTodayIds] itself — the union, the `taskId` (not `seriesId`) key, and the deliberate exclusions.
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

    /** A plan row of any kind — the plan stopped being Tasks-only in #385, so the join takes rows. */
    private fun planRow(id: String, kind: ItemKind = ItemKind.Task) = PlanRow(
        item = Item(id = id, kind = kind, title = id),
        task = task(id).takeIf { kind == ItemKind.Task },
    )

    // One habit's two ids, taken verbatim from the captured staging payload
    // `contracts/fixtures/items-sample.json` (its titles are scrubbed, so this names no habit): an item
    // carries a series id BESIDE its own id, never as it.
    private val habitItemId = "77dd6a6e-b936-4f61-9807-c3a6b647f9f1"
    private val habitSeriesId = "b7c21959-c5f6-4087-8ab2-7690c81e463a"

    /**
     * A feed row shaped as the wire actually is: [definitionId] is `task_id` — the recurring definition's
     * **own item id**, which is the value a tree row is keyed by — and [seriesId] is a *different* value
     * whose only job is to say "this row is a firing". Pass `seriesId = null` for a one-off dated row.
     *
     * [seriesId] defaults to a value *derived* from [definitionId] so a caller cannot accidentally reuse
     * one id as the other. That is the whole point: the helper this replaced took both as bare positional
     * strings, and every fixture invented a `taskId` matching no tree row — which made keying on
     * `seriesId` look correct and pinned the defect as intended behaviour.
     */
    private fun firing(
        definitionId: String,
        seriesId: String? = "$definitionId-series",
        kind: ItemKind? = ItemKind.Habit,
        source: CalendarSource = CalendarSource.Deferno,
    ) = CalendarItem(
        id = "feed-$definitionId-$today",
        taskId = definitionId,
        seriesId = seriesId,
        title = definitionId,
        date = today,
        start = epoch,
        end = epoch,
        allDay = true,
        status = WorkingState.Open,
        kind = kind,
        source = source,
    )

    // --- the join itself ---

    @Test
    fun unionsTodaysPlanTasksWithTodaysRecurringFirings() {
        val ids = inTodayIds(
            plan = listOf(planRow("t-1"), planRow("t-2")),
            day = listOf(firing(definitionId = "h-morning-run")),
        )

        assertEquals(setOf("t-1", "t-2", "h-morning-run"), ids)
    }

    /**
     * A recurring definition seeded onto the *plan* counts through the plan arm on its own id, without
     * needing a matching calendar firing (#385). Before the plan became kind-neutral this row could not
     * reach the join at all — the repository dropped it — so "In today" answered "no" for a Habit the
     * server had put on the day.
     */
    @Test
    fun aPlannedRecurringDefinitionCountsThroughThePlanArm() {
        val ids = inTodayIds(plan = listOf(planRow("h-walk", ItemKind.Habit)), day = emptyList())

        assertEquals(setOf("h-walk"), ids)
    }

    /**
     * The one thing this join must not get wrong (#386). `taskId` is the recurring definition's **own item
     * id** — the value the feed projects the firing from, and exactly what an Item tree row is keyed by.
     * `seriesId` is a *different* uuid naming the series, and no `Item` has a field for one — so keying on
     * it produces a set matching no tree row at all: an "In today" empty for every recurring item, which
     * reads as a confident "no". Real wire ids, because synthetic ones let this be asserted backwards.
     */
    @Test
    fun keysTheRecurringArmOnTheDefinitionItemIdNotTheSeriesId() {
        val ids = inTodayIds(plan = emptyList(), day = listOf(firing(habitItemId, seriesId = habitSeriesId)))

        assertEquals(setOf(habitItemId), ids)
        assertTrue(habitSeriesId !in ids, "the series id is NOT a tree row id — no Item carries one")
    }

    /**
     * A one-off dated Task in the feed (seriesId null) is in today because it is on the plan, never
     * because it merely carries a date — the reference client keeps those gates separate too. Under the
     * `taskId` key this is the load-bearing guard: the row's `taskId` IS its tree row's id, so nothing but
     * the explicit firing gate stops it.
     *
     * This is the real wire shape, which means it is series-less *and* Task-kinded — droppable twice over,
     * so on its own it cannot tell a series gate from a kind gate. [aSeriesCarryingRowIsInTodayWhateverItsKind]
     * is what isolates the series clause.
     */
    @Test
    fun dropsAMerelyDatedRowThatBelongsToNoSeries() {
        val ids = inTodayIds(
            plan = emptyList(),
            day = listOf(firing("t-dated", seriesId = null, kind = ItemKind.Task)),
        )

        assertEquals(emptySet(), ids)
    }

    /**
     * The gate is `source == Deferno && seriesId != null` and says **nothing** about kind — pinned here on
     * the one shape that can prove it: a Deferno row carrying a series whose kind is `Task`. Without this,
     * swapping the whole gate for `source == Deferno && kind != Task` passes every other case in this file,
     * because the only series-less fixture is also the only Task-kinded one. That mutant is not academic:
     * it is what "reuse [CalendarItem.isActionableOccurrence]" quietly becomes.
     */
    @Test
    fun aSeriesCarryingRowIsInTodayWhateverItsKind() {
        val ids = inTodayIds(plan = emptyList(), day = listOf(firing("t-with-series", kind = ItemKind.Task)))

        assertEquals(setOf("t-with-series"), ids)
    }

    /**
     * The write gate ([CalendarItem.isActionableOccurrence]) additionally demands a resolved kind, because
     * it picks a kind-scoped endpoint. This read gate must not borrow that clause: a habit whose kind
     * token this build predates still fires today, and dropping it here would recreate #386's symptom for
     * exactly the rows the tolerant decoder exists to keep visible.
     */
    @Test
    fun anUnresolvedKindFiringIsStillInToday() {
        val ids = inTodayIds(plan = emptyList(), day = listOf(firing("h-unknown-kind", kind = null)))

        assertEquals(setOf("h-unknown-kind"), ids)
    }

    /**
     * A synced Google event is stored as an Event-*kind* item, so under a `taskId` key its id is a real
     * tree row id — the gate's `source` clause is the only thing keeping it out, and it turns load-bearing
     * the day the provider's recurrence is expanded into firings.
     */
    @Test
    fun anExternalRowThatCarriesASeriesIsNotInToday() {
        val ids = inTodayIds(
            plan = emptyList(),
            day = listOf(firing("gcal-item", source = CalendarSource.External)),
        )

        assertEquals(emptySet(), ids)
    }

    @Test
    fun collapsesAnIdPresentOnBothSidesToOneEntry() {
        val ids = inTodayIds(plan = listOf(planRow("shared")), day = listOf(firing("shared")))

        assertEquals(setOf("shared"), ids)
    }

    @Test
    fun emptyOnBothSidesIsAnEmptySetNotEverything() {
        assertEquals(emptySet(), inTodayIds(plan = emptyList(), day = emptyList()))
    }

    // --- the shell wiring ---

    @Test
    fun theJoinedSetReachesTheItemTreeStateForBothKinds() = runTest {
        // Wire-realistic on purpose: the Habit's tree row is keyed by its ITEM id, and the feed row that
        // fires it carries that same id as `task_id` plus an unrelated `series_id`. That agreement is the
        // whole point of the join, so the fixture has to be able to break it.
        val calendar = RecordingCalendarRepository(day = listOf(firing(habitItemId, seriesId = habitSeriesId)))
        val shell = shell(plan = listOf(task("t-1")), calendar = calendar)
        shell.selectDestination(Destination.Tasks)
        val tree = (shell.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks).component.tree
        backgroundScope.launch { tree.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            setOf("t-1", habitItemId),
            tree.state.value.inTodayIds,
            "a planned Task AND a Habit firing today both reach the tree",
        )
        // The assertion above pins the set's *contents* but not that they are in the tree's id space —
        // and #386 was precisely an id-space mismatch, so a set of plausible-looking strings that names no
        // row is the failure to catch. Both sides of the join are checked against the rows that rendered.
        val rowIds = tree.state.value.rows.map { it.item.id }.toSet()
        assertEquals(
            tree.state.value.inTodayIds,
            tree.state.value.inTodayIds intersect rowIds,
            "every joined id must name a loaded tree row — otherwise 'In today' narrows to nothing",
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

        // Honours [date] rather than returning [day] unconditionally: the shell is supposed to subscribe to
        // TODAY's window, and a fake that ignores the argument cannot tell that from any other day.
        override fun observeDay(date: LocalDate): Flow<List<CalendarItem>> =
            MutableStateFlow(day.filter { it.date == date })

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
                Item(id = habitItemId, kind = ItemKind.Habit, title = "Take a Walk", sequence = 1),
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
