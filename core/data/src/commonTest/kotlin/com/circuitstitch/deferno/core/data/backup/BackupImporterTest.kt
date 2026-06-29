package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The on-device import/restore engine (#314, ADR-0041): parse a [Backup file][BackupImporter.import] zip,
 * version-gate its `{ version, data }` envelope, and replay each item as an **id-preserving create** on
 * the offline outbox (ADR-0034 dedupes on the client id → idempotent). Proved on the ADR-0006 JVM-fast
 * path against the in-memory store/outbox/pending-create fakes — the same fakes the create path uses.
 */
class BackupImporterTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun task(id: String, org: String? = null) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = "task-$id",
        workingState = WorkingState.Done,
        dateCreated = created,
        hydration = HydrationState.Full,
        description = "desc-$id",
        ownerOrgId = org?.let(::OrgId),
    )

    private fun habit(id: String) = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = "habit-$id",
        definitionState = DefinitionState.Active,
        recurrence = Recurrence(RecurrenceFrequency.Weekly, days = listOf("mon")),
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private fun chore(id: String) = Chore(
        id = ChoreId(id),
        orgSlug = "u-e4h2qk",
        title = "chore-$id",
        definitionState = DefinitionState.Active,
        recurrence = Recurrence(RecurrenceFrequency.Daily),
        cadenceMode = "rolling",
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private fun event(id: String) = Event(
        id = EventId(id),
        orgSlug = "u-e4h2qk",
        title = "event-$id",
        definitionState = DefinitionState.Active,
        completeBy = created,
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    /** A real Backup zip built by the export engine — proves import round-trips export by construction. */
    private suspend fun backupOf(
        tasks: List<Task> = emptyList(),
        habits: List<Habit> = emptyList(),
        chores: List<Chore> = emptyList(),
        events: List<Event> = emptyList(),
    ): ByteArray = BackupExporter(
        taskStore = FakeTaskLocalStore(tasks.associateBy { it.id }),
        habitStore = FakeHabitLocalStore(habits.associateBy { it.id }),
        choreStore = FakeChoreLocalStore(chores.associateBy { it.id }),
        eventStore = FakeEventLocalStore(events.associateBy { it.id }),
    ).buildBackupZip()

    private fun zipOf(manifest: String): ByteArray =
        zipStored(listOf(BackupExporter.MANIFEST_ENTRY to manifest.encodeToByteArray()))

    private class Fixture {
        val taskStore = FakeTaskLocalStore()
        val habitStore = FakeHabitLocalStore()
        val choreStore = FakeChoreLocalStore()
        val eventStore = FakeEventLocalStore()
        val outbox = FakeOutboxStore()
        val pending = FakePendingCreateStore()
        val importer = BackupImporter(
            taskStore, habitStore, choreStore, eventStore, outbox, pending,
            now = { Instant.parse("2026-06-28T00:00:00Z") },
        )
    }

    @Test
    fun imports_each_item_as_an_id_preserving_outbox_create() = runTest {
        val f = Fixture()
        val zip = backupOf(
            tasks = listOf(task("t1")),
            habits = listOf(habit("h1")),
            chores = listOf(chore("c1")),
            events = listOf(event("e1")),
        )

        val result = f.importer.import(zip)

        assertEquals(ImportResult.Restored(4), result)
        // Optimistic local upsert preserves the original id + fidelity (status, dateCreated).
        assertEquals(WorkingState.Done, f.taskStore.all[TaskId("t1")]?.workingState)
        assertEquals(created, f.taskStore.all[TaskId("t1")]?.dateCreated)
        assertTrue(f.habitStore.all.containsKey(HabitId("h1")))
        assertTrue(f.choreStore.all.containsKey(ChoreId("c1")))
        assertTrue(f.eventStore.all.containsKey(EventId("e1")))
        // Each item enqueues an id-preserving create the outbox replays when online.
        assertEquals(
            setOf("create:Task:t1", "create:Habit:h1", "create:Chore:c1", "create:Event:e1"),
            f.outbox.all.map { it.target }.toSet(),
        )
        assertTrue(f.outbox.all.single { it.target == "create:Task:t1" }.request.body!!.contains("\"id\":\"t1\""))
        // Pending-create rows protect the optimistic rows from orphan-purge until replay.
        assertEquals(setOf("t1", "h1", "c1", "e1"), f.pending.pendingIds())
    }

    @Test
    fun round_trip_restores_a_deleted_item_with_the_same_id() = runTest {
        val zip = backupOf(tasks = listOf(task("keep-me")))
        val f = Fixture() // a fresh, empty DB — the item was "deleted" before re-import

        f.importer.import(zip)

        assertEquals("keep-me", f.taskStore.all[TaskId("keep-me")]?.id?.value)
    }

    @Test
    fun reimporting_the_same_file_does_not_duplicate_rows() = runTest {
        val f = Fixture()
        val zip = backupOf(tasks = listOf(task("t1")), habits = listOf(habit("h1")))

        f.importer.import(zip)
        f.importer.import(zip)

        // Ids preserved → upsert replaces, never duplicates (the backend dedupes the replayed create).
        assertEquals(1, f.taskStore.all.size)
        assertEquals(1, f.habitStore.all.size)
    }

    @Test
    fun a_version_above_max_forces_an_upgrade_and_writes_nothing() = runTest {
        val f = Fixture()
        val result = f.importer.import(zipOf("""{"version":"9.9","data":[]}"""))

        assertEquals(ImportResult.ForceUpgrade, result)
        assertTrue(f.outbox.all.isEmpty())
        assertTrue(f.taskStore.all.isEmpty())
    }

    @Test
    fun a_version_below_min_is_refused_and_writes_nothing() = runTest {
        val f = Fixture()
        val result = f.importer.import(zipOf("""{"version":"0.0","data":[]}"""))

        assertEquals(ImportResult.Unsupported, result)
        assertTrue(f.outbox.all.isEmpty())
    }

    @Test
    fun a_malformed_file_fails_gracefully_without_partial_writes() = runTest {
        val f = Fixture()

        assertEquals(ImportResult.Malformed, f.importer.import(byteArrayOf(1, 2, 3, 4)))           // not a zip
        assertEquals(ImportResult.Malformed, f.importer.import(zipOf("""not json""")))             // bad manifest
        assertEquals(ImportResult.Malformed, f.importer.import(zipStored(listOf("readme.txt" to "hi".encodeToByteArray())))) // no manifest

        assertTrue(f.outbox.all.isEmpty())
        assertTrue(f.taskStore.all.isEmpty())
    }

    @Test
    fun imported_items_are_rehomed_so_the_create_carries_no_owner_org() = runTest {
        val f = Fixture()
        val zip = backupOf(tasks = listOf(task("t1", org = "some-other-org")))

        f.importer.import(zip)

        // The create path re-homes to the active account's personal org: the wire create carries no org.
        val body = f.outbox.all.single().request.body!!
        assertFalse(body.contains("owner_org"), body)
    }
}
