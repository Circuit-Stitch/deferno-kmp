package com.circuitstitch.deferno.core.data.create

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.habit.OfflineHabitRepository
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The online-only create/convert writer (ADR-0016, #71). Proves the three behaviours the acceptance
 * criteria pin: online create seeds the server-id row into the local store and the observe `Flow`
 * emits it (no manual refresh); offline create returns [CreateResult.Offline] and touches **nothing**
 * (no remote call, no local write — the ADR-0016 "enqueue nothing" guarantee); and convert reconciles
 * the cache (old-kind row removed, new-kind row seeded).
 */
class OnlineCreateWriterTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun task(id: String) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = "from server",
        workingState = WorkingState.Open,
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private fun habit(id: String) = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = "stretch",
        definitionState = DefinitionState.Active,
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private fun chore(id: String) = Chore(
        id = ChoreId(id),
        orgSlug = "u-e4h2qk",
        title = "trash",
        definitionState = DefinitionState.Active,
        dateCreated = created,
        hydration = HydrationState.Full,
    )

    private class Fixture {
        val connectivity = FakeConnectivity(online = true)
        val remote = FakeItemRemoteSource()
        val taskStore = FakeTaskLocalStore()
        val habitStore = FakeHabitLocalStore()
        val choreStore = FakeChoreLocalStore()
        val eventStore = FakeEventLocalStore()
        val writer = OnlineCreateWriter(connectivity, remote, taskStore, habitStore, choreStore, eventStore)
    }

    @Test
    fun onlineCreateTaskSeedsTheServerIdRowAndTheObserveFlowEmitsIt() = runTest {
        val f = Fixture()
        f.remote.taskResult = ApiResult.Success(task("server-1"))

        f.taskStore.observeActive().test {
            assertTrue(awaitItem().isEmpty())

            val result = f.writer.createTask(CreateTaskPayload(title = "buy milk"))

            assertEquals(CreateResult.Created(ItemKind.Task, "server-1"), result)
            // The created row appears via the local Flow with NO manual refresh.
            assertEquals(listOf(TaskId("server-1")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onlineCreateHabitSeedsTheHabitStoreAndTheRepositoryObservesIt() = runTest {
        val f = Fixture()
        f.remote.habitResult = ApiResult.Success(habit("h-1"))
        val repo = OfflineHabitRepository(f.habitStore)

        repo.observeHabits().test {
            assertTrue(awaitItem().isEmpty())
            val result = f.writer.createHabit(CreateHabitPayload(title = "stretch", recurrence = RecurrenceDto("daily")))
            assertEquals(CreateResult.Created(ItemKind.Habit, "h-1"), result)
            assertEquals(listOf(HabitId("h-1")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun offlineCreateReturnsOfflineAndEnqueuesNothingNorCallsTheServer() = runTest {
        val f = Fixture()
        f.connectivity.online.value = false

        val result = f.writer.createTask(CreateTaskPayload(title = "buy milk"))

        assertEquals(CreateResult.Offline, result)
        // ADR-0016: nothing enqueued, nothing called, nothing written.
        assertTrue(f.remote.calls.isEmpty(), "offline must make no network call")
        assertTrue(f.taskStore.all.isEmpty(), "offline must write nothing locally")
    }

    @Test
    fun aTransportFailureWhileOnlineSurfacesAsOfflineReconnectToSave() = runTest {
        val f = Fixture()
        f.remote.taskResult = ApiResult.Failure(ApiError.Transport(RuntimeException("no route")))

        val result = f.writer.createTask(CreateTaskPayload(title = "x"))

        // The network was unreachable mid-call → same gentle "reconnect to save", still nothing seeded.
        assertEquals(CreateResult.Offline, result)
        assertTrue(f.taskStore.all.isEmpty())
    }

    @Test
    fun aServerRejectionSurfacesAsFailedNotOffline() = runTest {
        val f = Fixture()
        f.remote.taskResult = ApiResult.Failure(ApiError.Endpoint(status = 422, code = "invalid", message = "title required"))

        val result = f.writer.createTask(CreateTaskPayload(title = ""))

        val failed = assertIs<CreateResult.Failed>(result)
        assertEquals("title required", failed.message)
    }

    @Test
    fun convertRemovesTheOldKindRowAndSeedsTheNewKindRow() = runTest {
        val f = Fixture()
        // Start with a Task cached; convert it to a Chore.
        f.taskStore.upsert(task("item-1"))
        f.remote.convertResult = ApiResult.Success(ConvertedItem.AsChore(chore("item-1")))

        val result = f.writer.convert("item-1", fromKind = ItemKind.Task, ConvertItemPayload(type = "chore", recurrence = RecurrenceDto("weekly")))

        assertEquals(CreateResult.Created(ItemKind.Chore, "item-1"), result)
        // Old-kind (Task) row gone, new-kind (Chore) row seeded.
        assertTrue(f.taskStore.all.isEmpty(), "the pre-convert Task row must be removed")
        assertEquals(chore("item-1"), f.choreStore.all[ChoreId("item-1")])
    }
}
