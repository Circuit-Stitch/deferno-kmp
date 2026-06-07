package com.circuitstitch.deferno.core.data.outbox

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.data.task.FakeTaskRemoteSource
import com.circuitstitch.deferno.core.data.task.OfflineTaskRepository
import com.circuitstitch.deferno.core.data.task.OutboxTaskWriter
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline → online transition end to end (ADR-0001, #23) — the acceptance criterion that ties the
 * whole write path together: writer (optimistic apply + enqueue) → outbox → processor (FIFO replay
 * with backoff) → reconcile. Wires the real engine to the in-memory cache fakes and a scriptable
 * "server" (the [FakeTaskRemoteSource] snapshot the reconcile pulls), on the ADR-0006 JVM-fast path.
 */
class OfflineToOnlineTest {

    private val t0 = Instant.parse("2026-06-07T12:00:00Z")
    private val created = Instant.parse("2026-05-20T16:11:42Z")
    private val a = TaskId("a")
    private val b = TaskId("b")

    private fun task(id: TaskId, state: WorkingState = WorkingState.Open, deletedAt: Instant? = null) = Task(
        id = id,
        orgSlug = "u-test",
        title = "task-${id.value}",
        workingState = state,
        dateCreated = created,
        deletedAt = deletedAt,
        hydration = HydrationState.Summary,
    )

    @Test
    fun writesApplyOptimisticallyOfflineThenReplayAndReconcileOnline() = runTest {
        // Local source of truth (the UI reads this); the "server" is the remote snapshot.
        val local = FakeTaskLocalStore(mapOf(a to task(a), b to task(b)))
        val remote = FakeTaskRemoteSource(snapshot = listOf(task(a), task(b)))
        val repository = OfflineTaskRepository(local, remote)
        val outbox = FakeOutboxStore()
        val writer = OutboxTaskWriter(local, outbox, now = { t0 })

        var online = false
        val sender = object : OutboxRequestSender {
            override suspend fun send(request: OutboxRequest): SendOutcome =
                if (online) SendOutcome.Success else SendOutcome.Retryable
        }
        val processor = OutboxProcessor(outbox, sender, reconcile = { repository.refresh() })

        // --- OFFLINE: user completes A and deletes B. Optimism shows immediately. ---
        writer.setWorkingState(a, WorkingState.Done)
        writer.delete(b)
        assertEquals(WorkingState.Done, local.all.getValue(a).workingState)
        assertTrue(local.all.getValue(b).isDeleted)
        assertEquals(2, outbox.all.size)

        // A flush while offline makes no progress: the head backs off, the queue is intact, no reconcile.
        val offline = processor.flush(t0)
        assertEquals(0, offline.succeeded)
        assertEquals(2L, outbox.count())
        assertEquals(WorkingState.Done, local.all.getValue(a).workingState) // optimism still stands

        // --- ONLINE: connectivity returns; the server has since applied both intents. ---
        online = true
        remote.snapshot = listOf(
            task(a, WorkingState.Done), // server accepted the completion
            task(b, deletedAt = Instant.parse("2026-06-07T12:00:05Z")), // server tombstoned B
        )

        // Flush past the backoff window: FIFO replay drains the queue, then reconcile pulls server truth.
        val onlineResult = processor.flush(t0 + 2.seconds)
        assertEquals(2, onlineResult.succeeded)
        assertEquals(0L, outbox.count())

        // Reconcile ran (LWW): cache matches the server; the tombstone is honoured.
        assertEquals(WorkingState.Done, local.all.getValue(a).workingState)
        assertTrue(local.all.getValue(b).isDeleted)

        // The UI-facing active list shows only A.
        repository.observeTasks().test {
            assertEquals(listOf(a), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
