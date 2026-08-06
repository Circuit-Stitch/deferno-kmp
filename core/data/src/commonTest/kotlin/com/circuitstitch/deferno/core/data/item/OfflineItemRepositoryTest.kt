package com.circuitstitch.deferno.core.data.item

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Expansion
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceCursor
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.SeriesOverride
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.expandOccurrenceGrid
import com.circuitstitch.deferno.core.model.recurrenceCursor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unified cross-kind read of [OfflineItemRepository] (ADR-0049, #226) — the read half of the Item
 * store the Tasks [Item tree] (#227) renders as one forest. Proves [observeItems] merges all four
 * per-kind caches into one list, projects each kind's common fields (incl. the de-emphasis [isTerminal]
 * signal and the Task-only subtree counts), re-emits when any kind changes, and that [refresh] delegates
 * the cross-kind `/items` cold sync to [ItemSync]. Runs on the ADR-0006 JVM-fast path against the fakes.
 *
 * It also pins the recurring pair (#384): the rule + its moving cursor reach the row on all three
 * recurring kinds and on **neither** for a Task, and an exhausted series survives as rule-without-cursor
 * rather than being flattened into "no deadline".
 */
class OfflineItemRepositoryTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun task(
        id: String,
        state: WorkingState = WorkingState.Open,
        parentId: String? = null,
        sequence: Long? = null,
        descendantDone: Long? = null,
        descendantTotal: Long? = null,
        blocked: Boolean = false,
        isBlocker: Boolean = false,
        completeBy: Instant? = null,
    ) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = "task-$id",
        workingState = state,
        parentId = parentId?.let(::TaskId),
        sequence = sequence,
        dateCreated = created,
        hydration = HydrationState.Full,
        descendantDone = descendantDone,
        descendantTotal = descendantTotal,
        blocked = blocked,
        isBlocker = isBlocker,
        completeBy = completeBy,
    )

    private fun habit(
        id: String,
        state: DefinitionState = DefinitionState.Active,
        parentId: String? = null,
        sequence: Long? = null,
        blocked: Boolean = false,
        isBlocker: Boolean = false,
        recurrence: Recurrence? = null,
        completeBy: Instant? = null,
    ) = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = "habit-$id",
        definitionState = state,
        parentId = parentId?.let(::TaskId),
        sequence = sequence,
        dateCreated = created,
        hydration = HydrationState.Full,
        blocked = blocked,
        isBlocker = isBlocker,
        recurrence = recurrence,
        completeBy = completeBy,
    )

    private fun chore(
        id: String,
        sequence: Long? = null,
        recurrence: Recurrence? = null,
        completeBy: Instant? = null,
    ) = Chore(
        id = ChoreId(id),
        orgSlug = "u-e4h2qk",
        title = "chore-$id",
        definitionState = DefinitionState.Active,
        sequence = sequence,
        dateCreated = created,
        hydration = HydrationState.Full,
        recurrence = recurrence,
        completeBy = completeBy,
    )

    private fun event(
        id: String,
        sequence: Long? = null,
        recurrence: Recurrence? = null,
        completeBy: Instant? = null,
    ) = Event(
        id = EventId(id),
        orgSlug = "u-e4h2qk",
        title = "event-$id",
        definitionState = DefinitionState.Active,
        sequence = sequence,
        dateCreated = created,
        hydration = HydrationState.Full,
        recurrence = recurrence,
        completeBy = completeBy,
    )

    private class Fixture(
        val tasks: FakeTaskLocalStore = FakeTaskLocalStore(),
        val habits: FakeHabitLocalStore = FakeHabitLocalStore(),
        val chores: FakeChoreLocalStore = FakeChoreLocalStore(),
        val events: FakeEventLocalStore = FakeEventLocalStore(),
        val source: FakeItemSnapshotSource = FakeItemSnapshotSource(),
        val pending: FakePendingCreateStore = FakePendingCreateStore(),
    ) {
        val sync = ItemSync(tasks, habits, chores, events, source, pending)
        val repository = OfflineItemRepository(tasks, habits, chores, events, sync)
    }

    @Test
    fun observeItemsMergesAllFourKindsIntoOneList() = runTest {
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h"))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c"))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e"))),
        )

        f.repository.observeItems().test {
            val items = awaitItem()
            assertEquals(setOf("t" to ItemKind.Task, "h" to ItemKind.Habit, "c" to ItemKind.Chore, "e" to ItemKind.Event), items.map { it.id to it.kind }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun projectsATaskWithItsParentTerminalStateAndSubtreeCounts() = runTest {
        val f = Fixture(
            tasks = FakeTaskLocalStore(
                mapOf(TaskId("child") to task("child", WorkingState.Done, parentId = "root", sequence = 7, descendantDone = 2, descendantTotal = 5)),
            ),
        )

        f.repository.observeItems().test {
            val item = awaitItem().single()
            assertEquals(Item(id = "child", kind = ItemKind.Task, title = "task-child", parentId = "root", sequence = 7, isTerminal = true, descendantDone = 2, descendantTotal = 5), item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun projectsAnActiveRecurringDefinitionAsNonTerminalWithNoSubtreeCounts() = runTest {
        val f = Fixture(habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", parentId = "root", sequence = 3))))

        f.repository.observeItems().test {
            val item = awaitItem().single()
            assertEquals("root", item.parentId)
            assertEquals(false, item.isTerminal)
            assertNull(item.descendantDone)
            assertNull(item.descendantTotal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun projectsServerDerivedBlockedAndBlockerFlagsAcrossKinds() = runTest {
        // #289: blocked/isBlocker are server-derived per item; the projection forwards them unchanged
        // for a Task and for a recurring kind (a habit can inherit `blocked` from a blocked ancestor).
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t", blocked = true, isBlocker = false))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", blocked = false, isBlocker = true))),
        )

        f.repository.observeItems().test {
            val byId = awaitItem().associateBy { it.id }
            assertEquals(true, byId.getValue("t").blocked)
            assertEquals(false, byId.getValue("t").isBlocker)
            assertEquals(false, byId.getValue("h").blocked)
            assertEquals(true, byId.getValue("h").isBlocker)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deEmphasizesAnArchivedRecurringDefinition() = runTest {
        val f = Fixture(habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", state = DefinitionState.Archived))))

        f.repository.observeItems().test {
            assertTrue(awaitItem().single().isTerminal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun carriesDefinitionStateOnRecurringRowsAndNullOnTasks() = runTest {
        // #299: the recurring "light switch" is carried through the projection (for the tree's command menu),
        // populated per kind; a Task has no definition state, so its row carries null (its lifecycle is
        // WorkingState). isTerminal is still derived (Archived → terminal) — this is the FULL state alongside it.
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", state = DefinitionState.InReview))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c"))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e"))),
        )

        f.repository.observeItems().test {
            val byId = awaitItem().associateBy { it.id }
            assertNull(byId.getValue("t").definitionState, "a Task carries no definition state")
            assertEquals(DefinitionState.InReview, byId.getValue("h").definitionState)
            assertEquals(DefinitionState.Active, byId.getValue("c").definitionState)
            assertEquals(DefinitionState.Active, byId.getValue("e").definitionState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun carriesTheRecurrenceRuleAndCursorOnRecurringRowsAndNeitherOnATask() = runTest {
        // #384: the projection now carries the recurring PAIR — the rule and its moving cursor — on all
        // three recurring kinds, so a row can read the series without loading the concrete kind. The Task
        // arm carries NEITHER, and deliberately so: its `completeBy` is a plain deadline, not a cursor,
        // and forwarding it would make every dated Task read as a due-or-exhausted series.
        val weekly = Recurrence(Cadence.Weekly(listOf("Mon", "Wed")), RecurrenceBound.AfterCount(4))
        val every3 = Recurrence(Cadence.EveryNDays(3))
        val yearly = Recurrence(Cadence.Yearly(interval = 1, month = 6, day = 14))
        val cursor = Instant.parse("2026-08-25T09:00:00Z")
        val f = Fixture(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t", completeBy = cursor))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", recurrence = weekly, completeBy = cursor))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c", recurrence = every3, completeBy = cursor))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e", recurrence = yearly, completeBy = cursor))),
        )

        f.repository.observeItems().test {
            val byId = awaitItem().associateBy { it.id }
            assertEquals(weekly, byId.getValue("h").recurrence)
            assertEquals(every3, byId.getValue("c").recurrence)
            assertEquals(yearly, byId.getValue("e").recurrence)
            listOf("h", "c", "e").forEach { assertEquals(cursor, byId.getValue(it).recurrenceCursorAt, "cursor on $it") }
            assertNull(byId.getValue("t").recurrence, "a Task has no recurrence rule")
            assertNull(byId.getValue("t").recurrenceCursorAt, "a Task's deadline is NOT a recurrence cursor")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aTreeRowCanExpandItsOwnGridWithoutOpeningTheItem() = runTest {
        // #410's reason for putting the inputs on this projection rather than only on the concrete kinds.
        // The wire ships `series` on every `/items` row, so the tree already has what an expansion needs;
        // a detail-only projection would have forced a per-row fetch on the Plan (#385) to reach a grid.
        // The assertion is the whole claim, end to end: rule + cursor + inputs off ONE tree row, straight
        // into the expander, real firing dates out — no concrete Habit loaded, no network.
        val inputs = SeriesInputs(
            anchorLocal = LocalDateTime.parse("2026-08-03T09:00:00"),
            tzid = "America/Los_Angeles",
            overrides = listOf(
                SeriesOverride(
                    recurrenceId = LocalDateTime.parse("2026-08-10T09:00:00"),
                    movedToLocal = LocalDateTime.parse("2026-08-12T18:00:00"),
                ),
            ),
        )
        val f = Fixture(
            habits = FakeHabitLocalStore(
                mapOf(
                    HabitId("h") to habit("h", recurrence = Recurrence(Cadence.Weekly(listOf("Mon"))))
                        .copy(seriesId = "s-1", series = inputs),
                ),
            ),
        )

        f.repository.observeItems().test {
            val row = awaitItem().single()
            assertEquals("s-1", row.seriesId)
            assertEquals(inputs, row.series)

            val expansion = expandOccurrenceGrid(
                recurrence = requireNotNull(row.recurrence),
                series = requireNotNull(row.series),
                from = LocalDate(2026, 8, 1),
                to = LocalDate(2026, 8, 24),
            )
            assertEquals(
                // Mondays from the 3rd, with the 10th moved to the Wednesday evening — so it renders on
                // the 12th while still keyed on the slot it came from.
                listOf("2026-08-03", "2026-08-12", "2026-08-17", "2026-08-24"),
                assertIs<Expansion.Firings>(expansion).firings.map { it.date.toString() },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aTaskCarriesNeitherASeriesIdNorInputs() = runTest {
        // `null` means two different things on this projection and only one of them is about series: for
        // a Task it means "not a series at all", for a recurring kind it is the backend's elision. The
        // Task arm must never acquire either field by accident.
        val f = Fixture(tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))))

        f.repository.observeItems().test {
            val item = awaitItem().single()
            assertNull(item.seriesId)
            assertNull(item.series)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun projectsAnExhaustedSeriesAsARuleWithNoCursor() = runTest {
        // The server clears `complete_by` when the bound is reached (backend ADR
        // `2026-06-02-recurrence-anchor-and-bound`), so an exhausted series arrives as rule-without-cursor.
        // The projection must preserve that shape verbatim — collapsing either half would erase the
        // distinction between "series ran out" and "not a series at all" that [recurrenceCursor] reads.
        val f = Fixture(
            habits = FakeHabitLocalStore(
                mapOf(HabitId("h") to habit("h", recurrence = Recurrence(Cadence.Daily, RecurrenceBound.AfterCount(4)), completeBy = null)),
            ),
        )

        f.repository.observeItems().test {
            val item = awaitItem().single()
            assertEquals(Recurrence(Cadence.Daily, RecurrenceBound.AfterCount(4)), item.recurrence)
            assertNull(item.recurrenceCursorAt)
            assertEquals(RecurrenceCursor.Exhausted, item.recurrenceCursor(TimeZone.UTC, LocalDate(2026, 6, 15)))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun projectsARecurringDefinitionWithNoRuleAsCarryingNeitherReading() = runTest {
        // A definition whose rule did not survive the wire still projects: rule null, cursor whatever it
        // was. The reading is NoCursor — the rule, not the cursor, is what says "this is a series".
        val f = Fixture(habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", completeBy = Instant.parse("2026-06-16T09:00:00Z")))))

        f.repository.observeItems().test {
            val item = awaitItem().single()
            assertNull(item.recurrence)
            assertEquals(RecurrenceCursor.NoCursor, item.recurrenceCursor(TimeZone.UTC, LocalDate(2026, 6, 15)))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun reEmitsWhenAnyKindChanges() = runTest {
        val f = Fixture()

        f.repository.observeItems().test {
            assertTrue(awaitItem().isEmpty())

            f.habits.upsert(habit("h"))
            assertEquals(listOf("h" to ItemKind.Habit), awaitItem().map { it.id to it.kind })

            f.tasks.upsert(task("t"))
            assertEquals(setOf("h", "t"), awaitItem().map { it.id }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshDelegatesTheCrossKindColdSyncToItemSync() = runTest {
        val f = Fixture()
        f.source.snapshot = ItemSnapshot(
            tasks = listOf(task("t")),
            habits = listOf(habit("h")),
            chores = listOf(chore("c")),
            events = listOf(event("e")),
        )
        assertTrue(f.repository.observeItems().first().isEmpty()) // empty caches before the pull

        f.repository.refresh()

        // refresh() commits all four per-kind reconciles before returning, so the next read is the
        // fully-merged set (not one of combine's per-commit intermediate emissions).
        assertEquals(setOf("t", "h", "c", "e"), f.repository.observeItems().first().map { it.id }.toSet())
    }
}
