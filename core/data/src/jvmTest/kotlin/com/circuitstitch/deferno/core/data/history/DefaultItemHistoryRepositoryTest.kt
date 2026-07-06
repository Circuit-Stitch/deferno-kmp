package com.circuitstitch.deferno.core.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.TaskActionDto
import com.circuitstitch.deferno.core.network.dto.TaskActionKind
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The offline-first item-history repository (ADR-0043, #197) over a real in-memory `DefernoDatabase`
 * (ADR-0006 JVM-fast path). Proves the full cache round-trip (a fetched action's kind serializes into
 * the payload, then decodes back to a domain [ItemHistoryEvent] on observe), the wholesale replace on
 * refresh (append-only server history has no stable id to merge on), and the offline-first contract: an
 * [RemoteSnapshot.Unavailable] pull leaves the cached history intact.
 */
class DefaultItemHistoryRepositoryTest {

    private val t0 = Instant.parse("2026-06-21T12:00:00Z")
    private val t1 = Instant.parse("2026-06-21T12:05:00Z")

    private fun newStore() = SqlDelightItemHistoryLocalStore(
        DefernoDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) }),
        Dispatchers.Unconfined,
    )

    private class FakeRemote(var snapshot: RemoteSnapshot<List<TaskActionDto>>) : ItemHistoryRemoteSource {
        override suspend fun fetchHistory(itemId: String) = snapshot
    }

    private fun action(kind: TaskActionKind, at: Instant) = TaskActionDto(kind = kind, recordedAt = at.toString())

    @Test
    fun refreshCachesActionsThatObserveDecodesToDomainEvents() = runTest {
        val store = newStore()
        val remote = FakeRemote(
            RemoteSnapshot.Available(
                listOf(
                    action(TaskActionKind.Created, t0),
                    action(TaskActionKind.StatusChanged(TaskStatusWire.Open, TaskStatusWire.Done), t1),
                ),
            ),
        )
        val repo = DefaultItemHistoryRepository(store, remote)

        repo.refresh("t-1")

        val events = repo.observe("t-1").first()
        assertEquals(ItemHistoryEvent.Created(t0), events[0])
        assertEquals(ItemHistoryEvent.StatusChanged(t1, WorkingState.Open, WorkingState.Done), events[1])
    }

    @Test
    fun refreshReplacesTheCachedHistoryWholesale() = runTest {
        val store = newStore()
        val remote = FakeRemote(RemoteSnapshot.Available(listOf(action(TaskActionKind.Created, t0))))
        val repo = DefaultItemHistoryRepository(store, remote)
        repo.refresh("t-1")

        // A second, fuller fetch replaces the item's rows — never appends/duplicates.
        remote.snapshot = RemoteSnapshot.Available(
            listOf(action(TaskActionKind.Created, t0), action(TaskActionKind.Updated(listOf("title")), t1)),
        )
        repo.refresh("t-1")

        val events = repo.observe("t-1").first()
        assertEquals(2, events.size)
        assertEquals(ItemHistoryEvent.Updated(t1, listOf("title")), events[1])
    }

    @Test
    fun refreshLeavesTheCacheIntactWhenUnavailable() = runTest {
        val store = newStore()
        val remote = FakeRemote(RemoteSnapshot.Available(listOf(action(TaskActionKind.Created, t0))))
        val repo = DefaultItemHistoryRepository(store, remote)
        repo.refresh("t-1")

        remote.snapshot = RemoteSnapshot.Unavailable
        repo.refresh("t-1")

        assertEquals(listOf(ItemHistoryEvent.Created(t0)), repo.observe("t-1").first())
    }
}
