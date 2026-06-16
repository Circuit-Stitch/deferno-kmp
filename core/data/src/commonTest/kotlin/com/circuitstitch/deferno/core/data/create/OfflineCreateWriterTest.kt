package com.circuitstitch.deferno.core.data.create

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The offline-first create writer (#185, ADR-0001 forward path from ADR-0016). Proves the acceptance
 * criteria: creating any Item kind inserts the local row under a client-generated UUID immediately,
 * records a *pending* side-table row, and enqueues exactly one replayable `POST /{kind}` whose body
 * carries that id — **regardless of connectivity** (no offline refusal). Convert stays online-only.
 */
class OfflineCreateWriterTest {

    private val t0 = Instant.parse("2026-06-07T12:00:00Z")

    private class Fixture(online: Boolean = true, private val id: String = "client-1") {
        val connectivity = FakeConnectivity(online = online)
        val remote = FakeItemRemoteSource()
        val taskStore = FakeTaskLocalStore()
        val habitStore = FakeHabitLocalStore()
        val choreStore = FakeChoreLocalStore()
        val eventStore = FakeEventLocalStore()
        val outbox = FakeOutboxStore()
        val pending = FakePendingCreateStore()
        val writer = OfflineCreateWriter(
            connectivity = connectivity,
            remoteSource = remote,
            taskStore = taskStore,
            habitStore = habitStore,
            choreStore = choreStore,
            eventStore = eventStore,
            outbox = outbox,
            pendingCreateStore = pending,
            newId = { id },
            now = { Instant.parse("2026-06-07T12:00:00Z") },
            orgSlug = { "u-test" },
        )
    }

    @Test
    fun offlineCreateTaskInsertsLocalRowUnderClientIdAndEnqueuesOneCreate() = runTest {
        val f = Fixture(online = false)

        f.taskStore.observeActive().test {
            assertTrue(awaitItem().isEmpty())

            val result = f.writer.createTask(CreateTaskPayload(title = "buy milk", description = "2%"))

            // Offline-first: the create succeeds locally with the client id (never Offline).
            assertEquals(CreateResult.Created(ItemKind.Task, "client-1"), result)
            // The row appears via the local Flow immediately, under the client-generated id.
            val emitted = awaitItem()
            assertEquals(listOf(TaskId("client-1")), emitted.map { it.id })
            assertEquals("buy milk", emitted.single().title)
            cancelAndIgnoreRemainingEvents()
        }

        // Exactly one create entry, addressed by the create target, body carrying the client id.
        val entry = f.outbox.all.single()
        assertEquals("create:Task:client-1", entry.target)
        assertEquals(OutboxMethod.Post, entry.request.method)
        assertEquals(listOf("tasks"), entry.request.path)
        val body = Json.parseToJsonElement(entry.request.body!!).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("client-1", body["id"]?.jsonPrimitive?.content)
        assertEquals("buy milk", body["title"]?.jsonPrimitive?.content)

        // A pending side-table row was recorded.
        val pending = f.pending.all.single()
        assertEquals("client-1", pending.itemId)
        assertEquals(ItemKind.Task, pending.itemKind)
        assertEquals(PendingCreateState.Pending, pending.state)
    }

    @Test
    fun offlineCreateHabitSeedsTheHabitStoreAndEnqueuesACreate() = runTest {
        val f = Fixture(online = false, id = "h-1")

        val result = f.writer.createHabit(CreateHabitPayload(title = "stretch", recurrence = RecurrenceDto("daily")))

        assertEquals(CreateResult.Created(ItemKind.Habit, "h-1"), result)
        assertEquals(DefinitionState.Active, f.habitStore.all.getValue(HabitId("h-1")).definitionState)
        assertEquals("create:Habit:h-1", f.outbox.all.single().target)
        assertEquals(ItemKind.Habit, f.pending.all.single().itemKind)
    }

    @Test
    fun onlineCreateBehavesIdenticallyToOffline_stillEnqueuesNeverCallsTheServerDirectly() = runTest {
        val f = Fixture(online = true)

        val result = f.writer.createTask(CreateTaskPayload(title = "x"))

        assertEquals(CreateResult.Created(ItemKind.Task, "client-1"), result)
        // Create no longer POSTs directly — it always rides the outbox (the replay does the POST).
        assertTrue(f.remote.calls.isEmpty(), "create must not call the remote source directly")
        assertEquals(1, f.outbox.all.size)
    }

    // --- convert stays online-only (ADR-0016) ---

    @Test
    fun convertOfflineReturnsOfflineAndTouchesNothing() = runTest {
        val f = Fixture(online = false)
        f.taskStore.upsert(task("item-1"))

        val result = f.writer.convert("item-1", fromKind = ItemKind.Task, ConvertItemPayload(type = "chore"))

        assertEquals(CreateResult.Offline, result)
        assertTrue(f.remote.calls.isEmpty(), "offline convert must make no network call")
    }

    @Test
    fun convertOnlineReconcilesTheCache() = runTest {
        val f = Fixture(online = true)
        f.taskStore.upsert(task("item-1"))
        f.remote.convertResult = ApiResult.Success(ConvertedItem.AsChore(chore("item-1")))

        val result = f.writer.convert("item-1", fromKind = ItemKind.Task, ConvertItemPayload(type = "chore", recurrence = RecurrenceDto("weekly")))

        assertEquals(CreateResult.Created(ItemKind.Chore, "item-1"), result)
        assertTrue(f.taskStore.all.isEmpty(), "the pre-convert Task row must be removed")
        assertEquals(chore("item-1"), f.choreStore.all[ChoreId("item-1")])
    }

    @Test
    fun convertServerRejectionSurfacesAsFailed() = runTest {
        val f = Fixture(online = true)
        f.taskStore.upsert(task("item-1"))
        f.remote.convertResult = ApiResult.Failure(ApiError.Endpoint(status = 422, code = "invalid", message = "nope"))

        val result = f.writer.convert("item-1", fromKind = ItemKind.Task, ConvertItemPayload(type = "chore"))

        assertEquals("nope", assertIs<CreateResult.Failed>(result).message)
    }

    private fun task(id: String) = com.circuitstitch.deferno.core.model.Task(
        id = TaskId(id),
        orgSlug = "u-test",
        title = "t",
        workingState = WorkingState.Open,
        dateCreated = t0,
    )

    private fun chore(id: String) = Chore(
        id = ChoreId(id),
        orgSlug = "u-test",
        title = "trash",
        definitionState = DefinitionState.Active,
        dateCreated = t0,
    )
}
