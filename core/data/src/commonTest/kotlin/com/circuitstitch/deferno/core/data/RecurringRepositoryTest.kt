package com.circuitstitch.deferno.core.data

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.chore.OfflineChoreRepository
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.event.OfflineEventRepository
import com.circuitstitch.deferno.core.data.habit.OfflineHabitRepository
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline-first recurring read repositories (ADR-0001, #71): each observes its local-store `Flow`
 * only and re-emits on upsert, mirroring `OfflineTaskRepository`'s observe semantics — so a created
 * definition surfaces with no manual refresh (the issue's acceptance criterion).
 */
class RecurringRepositoryTest {

    private val created = Instant.parse("2026-05-04T01:53:05Z")

    @Test
    fun habitRepositoryObservesItsStoreAndReEmitsOnUpsert() = runTest {
        val store = FakeHabitLocalStore()
        val repo = OfflineHabitRepository(store)

        repo.observeHabits().test {
            assertTrue(awaitItem().isEmpty())
            store.upsert(
                Habit(id = HabitId("h-1"), orgSlug = "u-e4h2qk", title = "stretch", definitionState = DefinitionState.Active, dateCreated = created),
            )
            assertEquals(listOf(HabitId("h-1")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun choreRepositoryObservesById() = runTest {
        val chore = Chore(id = ChoreId("c-1"), orgSlug = "u-e4h2qk", title = "trash", definitionState = DefinitionState.Active, dateCreated = created)
        val repo = OfflineChoreRepository(FakeChoreLocalStore(mapOf(chore.id to chore)))

        repo.observeChore(ChoreId("c-1")).test {
            assertEquals("trash", awaitItem()?.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun eventRepositoryObservesList() = runTest {
        val event = Event(id = EventId("e-1"), orgSlug = "u-e4h2qk", title = "standup", definitionState = DefinitionState.Active, dateCreated = created)
        val repo = OfflineEventRepository(FakeEventLocalStore(mapOf(event.id to event)))

        repo.observeEvents().test {
            assertEquals(listOf(EventId("e-1")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
