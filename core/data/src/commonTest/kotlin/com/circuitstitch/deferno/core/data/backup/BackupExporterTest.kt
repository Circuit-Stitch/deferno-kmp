package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.Envelope
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The on-device export engine (#313, ADR-0041): the `items.json` it serializes **is** the REST
 * response envelope `{ version, data }`, carrying the same snake-case `core:network` DTO shapes the
 * API's read endpoints emit. Covers AC #6 — envelope shape, version `0.1`, DTO fidelity, and the
 * `external`-provenance exclusion — on the ADR-0006 JVM-fast path against the in-memory store fakes.
 */
class BackupExporterTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun task(
        id: String,
        title: String = "task-$id",
        state: WorkingState = WorkingState.Open,
        description: String? = "desc-$id",
        hydration: HydrationState = HydrationState.Full,
        external: ExternalRef? = null,
    ) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = title,
        workingState = state,
        dateCreated = created,
        hydration = hydration,
        description = description,
        external = external,
    )

    private fun habit(id: String) = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = "habit-$id",
        definitionState = DefinitionState.Active,
        recurrence = Recurrence(RecurrenceFrequency.Weekly, days = listOf("mon", "wed")),
        dateCreated = created,
        hydration = HydrationState.Full,
        description = "desc-$id",
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
        allDay = false,
        completeBy = created,
        endTime = Instant.parse("2026-05-20T17:11:42Z"),
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private fun exporter(
        tasks: List<Task> = emptyList(),
        habits: List<Habit> = emptyList(),
        chores: List<Chore> = emptyList(),
        events: List<Event> = emptyList(),
    ) = BackupExporter(
        taskStore = FakeTaskLocalStore(tasks.associateBy { it.id }),
        habitStore = FakeHabitLocalStore(habits.associateBy { it.id }),
        choreStore = FakeChoreLocalStore(chores.associateBy { it.id }),
        eventStore = FakeEventLocalStore(events.associateBy { it.id }),
    )

    private fun decode(json: String): Envelope<List<ItemView>> =
        DefernoJson.decodeFromString(Envelope.serializer(ListSerializer(ItemView.serializer())), json)

    @Test
    fun itemsJson_is_a_versioned_envelope() = runTest {
        val itemsJson = exporter(tasks = listOf(task("t1"))).buildItemsJson()
        val envelope = decode(itemsJson)
        assertEquals("0.1", envelope.version)
        assertEquals(1, envelope.data.size)
    }

    @Test
    fun task_serializes_in_snake_case_dto_shape_with_fidelity() = runTest {
        val itemsJson = exporter(
            tasks = listOf(task("t1", title = "Buy milk", state = WorkingState.InProgress, description = "2%")),
        ).buildItemsJson()

        // The wire shape: discriminated cross-kind union, snake_case keys (ADR-0011 DTOs).
        assertTrue(itemsJson.contains("\"type\":\"task\""), itemsJson)
        assertTrue(itemsJson.contains("\"date_created\""), itemsJson)
        assertTrue(itemsJson.contains("\"org_slug\""), itemsJson)

        val view = decode(itemsJson).data.single() as ItemView.Task
        assertEquals("t1", view.id)
        assertEquals("Buy milk", view.title)
        assertEquals("2%", view.description)
        assertEquals(TaskStatusWire.InProgress, view.status)
        assertEquals(created.toString(), view.dateCreated)
    }

    @Test
    fun all_four_kinds_are_exported_with_their_discriminator() = runTest {
        val itemsJson = exporter(
            tasks = listOf(task("t1")),
            habits = listOf(habit("h1")),
            chores = listOf(chore("c1")),
            events = listOf(event("e1")),
        ).buildItemsJson()

        val kinds = decode(itemsJson).data.map {
            when (it) {
                is ItemView.Task -> "task"
                is ItemView.Habit -> "habit"
                is ItemView.Chore -> "chore"
                is ItemView.Event -> "event"
            }
        }
        assertEquals(listOf("task", "habit", "chore", "event"), kinds)
    }

    @Test
    fun recurring_kinds_round_trip_their_recurrence_and_cadence() = runTest {
        val itemsJson = exporter(
            habits = listOf(habit("h1")),
            chores = listOf(chore("c1")),
        ).buildItemsJson()
        val data = decode(itemsJson).data

        val habitView = data.filterIsInstance<ItemView.Habit>().single()
        assertEquals("weekly", habitView.recurrence?.type)
        assertEquals(listOf("mon", "wed"), habitView.recurrence?.days)

        val choreView = data.filterIsInstance<ItemView.Chore>().single()
        assertEquals("daily", choreView.recurrence?.type)
        assertEquals("rolling", choreView.cadenceMode)
    }

    @Test
    fun external_provenance_items_are_omitted() = runTest {
        val native = task("t1")
        val imported = task("t2", external = ExternalRef(ItemSource.GitHub, "owner/repo#5", url = null))
        val itemsJson = exporter(tasks = listOf(native, imported)).buildItemsJson()

        val ids = decode(itemsJson).data.filterIsInstance<ItemView.Task>().map { it.id }
        assertEquals(listOf("t1"), ids)
    }

    @Test
    fun unhydrated_task_exports_without_a_description() = runTest {
        val summary = task("t1", description = null, hydration = HydrationState.Summary)
        val itemsJson = exporter(tasks = listOf(summary)).buildItemsJson()

        assertFalse(itemsJson.contains("\"description\""), itemsJson)
        assertNull(decode(itemsJson).data.filterIsInstance<ItemView.Task>().single().description)
    }

    @Test
    fun empty_db_exports_an_empty_data_array() = runTest {
        val envelope = decode(exporter().buildItemsJson())
        assertEquals("0.1", envelope.version)
        assertTrue(envelope.data.isEmpty())
    }

    @Test
    fun backup_zip_starts_with_the_pk_magic() = runTest {
        val zip = exporter(tasks = listOf(task("t1"))).buildBackupZip()
        assertEquals('P'.code.toByte(), zip[0])
        assertEquals('K'.code.toByte(), zip[1])
    }
}
