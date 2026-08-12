package com.circuitstitch.deferno.core.data.plugin

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import com.circuitstitch.deferno.core.model.plugin.Outcome
import com.circuitstitch.deferno.core.model.plugin.plugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The plugin-shaped read facade over the local stores (#421, ADR-0055/0056), on the ADR-0006 JVM-fast
 * path against the in-memory fakes.
 *
 * What is proved here is the *facade*: that it fans out over four tables and a fifth, that its output
 * is plugin-shaped and total, and that a caller never says which kind. That the translation itself is
 * faithful is `core:model`'s round-trip gate, and that the result agrees with what ships today is
 * [PluginReadParityTest] — neither is re-tested here.
 */
class OfflinePluginItemRepositoryTest {

    private val created = Instant.parse("2026-04-02T09:15:00Z")
    private val doneAt = Instant.parse("2026-08-11T18:02:00Z")
    private val day = LocalDate(2026, 8, 11)

    // ── The cross-kind list ────────────────────────────────────────────────────────────────────

    @Test
    fun theWholeCatalogReadsAsOnePluginShapedListAcrossAllFourKinds() = runTest {
        val repository = repository(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h"))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c"))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e"))),
        )

        val items = repository.observeItems().first()

        // Same rows, same order as the shipped cross-kind read: each store's own order, concatenated
        // Task → Habit → Chore → Event. A caller reading both projections sees the same list twice.
        assertContentEquals(listOf("t", "h", "c", "e"), items.map { it.core.id })
        // And the kind is gone: what distinguishes them now is which lifecycle they carry.
        assertIs<Lifecycle.Working>(items[0].progress.lifecycle)
        assertIs<Lifecycle.Definition>(items[1].progress.lifecycle)
    }

    @Test
    fun theListReEmitsWhenAnySingleKindsCacheChanges() = runTest {
        val habits = FakeHabitLocalStore()
        val repository = repository(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = habits,
        )

        repository.observeItems().test {
            assertContentEquals(listOf("t"), awaitItem().map { it.core.id })

            habits.upsert(habit("h"))

            assertContentEquals(listOf("t", "h"), awaitItem().map { it.core.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun everyRowTheListEmitsIsAValidPluginRecord() = runTest {
        // The runtime half of what named fields gave for free (ADR-0055): one member per family, and
        // each plugin on the record its scope names. Cheap to state here, and it is the assertion that
        // would catch a future recipe loading an Occurrence-scoped plugin onto a definition.
        val repository = repository(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h"))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c"))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e"))),
        )

        for (item in repository.observeItems().first()) {
            assertEquals(emptyList(), item.validate(), "${item.core.id} is not a valid plugin record")
        }
    }

    // ── The single-row read ────────────────────────────────────────────────────────────────────

    @Test
    fun oneRowIsFoundInWhicheverTableHoldsIt() = runTest {
        val repository = repository(
            tasks = FakeTaskLocalStore(mapOf(TaskId("t") to task("t"))),
            habits = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h"))),
            chores = FakeChoreLocalStore(mapOf(ChoreId("c") to chore("c"))),
            events = FakeEventLocalStore(mapOf(EventId("e") to event("e"))),
        )

        // The point of the facade in one assertion: four tables, four raw ids, no kind from the caller.
        for (id in listOf("t", "h", "c", "e")) {
            assertEquals(id, repository.observe(id).first()?.core?.id, "$id was not found")
        }
    }

    @Test
    fun anIdThisDeviceDoesNotHoldReadsAsNullRatherThanAnEmptyRecord() = runTest {
        // Cold start, a deep link, a row outside the snapshot window. `null` is the honest answer and
        // is the one read on this facade that has one — unlike a firing, an item has no key-only form.
        assertNull(repository().observe("never-cached").first())
    }

    @Test
    fun aTombstoneIsEmittedByTheSingleReadAndFilteredFromTheList() = runTest {
        val deleted = task("t").copy(deletedAt = Instant.parse("2026-08-01T00:00:00Z"))
        val repository = repository(tasks = FakeTaskLocalStore(mapOf(TaskId("t") to deleted)))

        // A tombstone is not absent, and `core.isDeleted` is what says so — the per-kind stores' own
        // contract for a single-row observe, carried through the recipe unchanged.
        val row = assertNotNull(repository.observe("t").first())
        assertTrue(row.core.isDeleted)

        // The list read excludes it, exactly as every other cross-kind list does.
        assertEquals(emptyList(), repository.observeItems().first())
    }

    // ── The firing read ────────────────────────────────────────────────────────────────────────

    @Test
    fun aStoredFactReadsAsAnOutcomeWhicheverKindsRowHoldsIt() = runTest {
        // Once per kind that can store a firing at all, driven off the same clamp the facade fans out
        // over — so a kind gaining stored firings is covered here without this test being edited.
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            val facts = FakeFiringStore(
                OccurrenceFact(kind, "def-1", day, OccurrenceResolution.DoneOnTime, doneAt = doneAt),
            )
            val firing = repository(facts = facts).observeFiring("def-1", day).first()

            assertEquals("def-1", firing.itemId)
            assertEquals(day, firing.date)
            assertEquals(OccurrenceResolution.DoneOnTime, firing.outcome.resolution, "$kind did not read back")
            assertEquals(doneAt, firing.outcome.doneAt)
            assertTrue(firing.outcome.isOnRecord)
            assertEquals(emptyList(), firing.validate())
        }
    }

    @Test
    fun aDateWithNothingOnRecordIsAnOccurrenceCarryingNothingRatherThanAnAbsentOne() = runTest {
        val firing = repository().observeFiring("def-1", day).first()

        // Absence is a value (ADR-0056, #436 amendment). The key always exists, so the read is total;
        // what is empty is the record, and the Scheduled-versus-Missed reading over it stays derived.
        assertEquals("def-1", firing.itemId)
        assertEquals(day, firing.date)
        assertEquals(emptyList(), firing.plugins)
        assertFalse(firing.outcome.isOnRecord)
        // Not the same claim as a stored `Scheduled`, which an Event row genuinely holds — the
        // distinction the whole nullable-resolution design exists to keep, so read it raw here.
        assertNull(firing.plugin<Outcome>())
    }

    @Test
    fun aStoredScheduledStaysDistinguishableFromNothingOnRecord() = runTest {
        val facts = FakeFiringStore(
            OccurrenceFact(ItemKind.Event, "def-1", day, OccurrenceResolution.Scheduled),
        )
        val firing = repository(facts = facts).observeFiring("def-1", day).first()

        // The server holds a row that records no progress. `saysSomething` is true for it, so the
        // plugin loads and the two absences never collapse into one.
        assertEquals(Outcome(OccurrenceResolution.Scheduled), firing.plugin<Outcome>())
        assertTrue(firing.outcome.isOnRecord)
    }

    @Test
    fun theFiringReadIsFannedOutOverExactlyTheKindsThatCanStoreOne() = runTest {
        val facts = FakeFiringStore()
        repository(facts = facts).observeFiring("def-1", day).first()

        // Read off `Clamp.storedResolutions` rather than written out as the three recurring kinds, so
        // a Task growing occurrences — which ADR-0055 expects — is one edit in the clamp and not two.
        assertEquals(setOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event), facts.asked)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────

    private fun repository(
        tasks: FakeTaskLocalStore = FakeTaskLocalStore(),
        habits: FakeHabitLocalStore = FakeHabitLocalStore(),
        chores: FakeChoreLocalStore = FakeChoreLocalStore(),
        events: FakeEventLocalStore = FakeEventLocalStore(),
        facts: OccurrenceFactLocalStore = FakeFiringStore(),
    ) = OfflinePluginItemRepository(tasks, habits, chores, events, facts)

    private fun task(id: String) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = "Task $id",
        workingState = WorkingState.Open,
        sequence = 1,
        dateCreated = created,
    )

    private fun habit(id: String) = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = "Habit $id",
        definitionState = DefinitionState.Active,
        sequence = 2,
        dateCreated = created,
    )

    private fun chore(id: String) = Chore(
        id = ChoreId(id),
        orgSlug = "u-e4h2qk",
        title = "Chore $id",
        definitionState = DefinitionState.Active,
        sequence = 3,
        dateCreated = created,
    )

    private fun event(id: String) = Event(
        id = EventId(id),
        orgSlug = "u-e4h2qk",
        title = "Event $id",
        definitionState = DefinitionState.Active,
        sequence = 4,
        dateCreated = created,
    )
}

/**
 * An in-memory [OccurrenceFactLocalStore] keyed as the real table is, `(kind, definitionId, date)`,
 * that also **records which kinds were asked about** — the fan-out is part of the facade's contract, so
 * it needs to be observable rather than inferred from a result that would look the same either way.
 */
private class FakeFiringStore(vararg initial: OccurrenceFact) : OccurrenceFactLocalStore {

    private val rows = MutableStateFlow(initial.associateBy { Triple(it.kind, it.definitionId, it.date) })

    /** Every kind [observe] has been called for. */
    val asked = mutableSetOf<ItemKind>()

    override fun observe(kind: ItemKind, definitionId: String, date: LocalDate): Flow<OccurrenceFact?> {
        asked += kind
        return rows.map { it[Triple(kind, definitionId, date)] }
    }

    override suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate): OccurrenceFact? =
        rows.value[Triple(kind, definitionId, date)]

    override suspend fun upsert(fact: OccurrenceFact) {
        rows.value = rows.value + (Triple(fact.kind, fact.definitionId, fact.date) to fact)
    }

    override suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate) {
        rows.value = rows.value - Triple(kind, definitionId, date)
    }

    // The facade reads one firing and never a span, so the range reads stay unimplemented rather than
    // faked — #421 adds a range read when a consumer needs one, and a fake answering more than the
    // seam does would hide that.
    override fun observeOn(date: LocalDate): Flow<List<OccurrenceFact>> = unsupported()

    override fun observeInRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<OccurrenceFact>> = unsupported()

    override suspend fun replaceRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
        facts: List<OccurrenceFact>,
    ): Unit = unsupported()

    override suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit) = block(this)

    private fun unsupported(): Nothing = error("the read facade does not use this seam")
}
