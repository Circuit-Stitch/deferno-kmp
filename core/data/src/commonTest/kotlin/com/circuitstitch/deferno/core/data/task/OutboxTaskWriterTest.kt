package com.circuitstitch.deferno.core.data.task

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Task write path (ADR-0001, #23): [OutboxTaskWriter] applies each intent optimistically to the
 * local cache (so the UI reflects it instantly) and enqueues its idempotent request to the outbox for
 * replay. Run against the in-memory fakes (ADR-0006 JVM-fast path).
 */
class OutboxTaskWriterTest {

    private val now = Instant.parse("2026-06-07T12:00:00Z")
    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun task(id: String, state: WorkingState = WorkingState.Open) = Task(
        id = TaskId(id),
        orgSlug = "u-test",
        title = "task-$id",
        workingState = state,
        dateCreated = created,
        hydration = HydrationState.Summary,
    )

    private fun writer(local: FakeTaskLocalStore, outbox: FakeOutboxStore) =
        OutboxTaskWriter(local, outbox, now = { now })

    @Test
    fun setWorkingStateAppliesOptimisticallyAndEnqueues() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to task("a")))
        val outbox = FakeOutboxStore()

        writer(local, outbox).setWorkingState(TaskId("a"), WorkingState.Done)

        // Optimistic local apply — visible immediately, before any network.
        assertEquals(WorkingState.Done, local.all.getValue(TaskId("a")).workingState)
        // Enqueued, ready to dispatch now.
        val entry = outbox.all.single()
        assertEquals("task:a", entry.target)
        assertEquals(OutboxMethod.Patch, entry.request.method)
        assertEquals(listOf("tasks", "a"), entry.request.path)
        assertEquals("""{"status":"done"}""", entry.request.body)
        assertEquals(now, entry.nextAttemptAt)
    }

    @Test
    fun deleteTombstonesLocallyAndEnqueuesABodilessDelete() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to task("a")))
        val outbox = FakeOutboxStore()

        writer(local, outbox).delete(TaskId("a"))

        val row = local.all.getValue(TaskId("a"))
        assertTrue(row.isDeleted)
        assertEquals(now, row.deletedAt) // optimistic tombstone at the injected clock
        val entry = outbox.all.single()
        assertEquals(OutboxMethod.Delete, entry.request.method)
        assertEquals(null, entry.request.body)
    }

    @Test
    fun aWriteToAnAbsentRowSkipsTheApplyButStillEnqueues() = runTest {
        val local = FakeTaskLocalStore() // empty
        val outbox = FakeOutboxStore()

        writer(local, outbox).rename(TaskId("ghost"), "renamed")

        assertTrue(local.all.isEmpty()) // no phantom row materialised
        assertEquals(1, outbox.all.size) // the write is not lost — it will reconcile on replay
        assertEquals("""{"title":"renamed"}""", outbox.all.single().request.body)
    }

    @Test
    fun capturesTheOldValuesAsTheLedgerBeforeImage() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to task("a", WorkingState.InProgress)))
        val outbox = FakeOutboxStore()

        writer(local, outbox).setWorkingState(TaskId("a"), WorkingState.Done)

        // The request body carries the NEW value (for replay); the ledger before-image carries the OLD one.
        assertEquals("""{"status":"done"}""", outbox.all.single().request.body)
        assertEquals("""{"status":"in-progress"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun anUnhydratedDescriptionEditOmitsTheOldBodyRatherThanFakingEmpty() = runTest {
        // Summary hydration: the cached description is null even if the server holds text, so the old body
        // is unknown — the before-image omits the key (rendered "previously unavailable"), not a false empty.
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to task("a")))
        val outbox = FakeOutboxStore()

        writer(local, outbox).setDescription(TaskId("a"), "new body")

        assertEquals("""{"description":"new body"}""", outbox.all.single().request.body)
        assertEquals("{}", outbox.enqueuedBefore.single())
    }

    @Test
    fun aHydratedDescriptionEditCapturesTheOldBody() = runTest {
        val full = task("a").copy(hydration = HydrationState.Full, description = "old body")
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to full))
        val outbox = FakeOutboxStore()

        writer(local, outbox).setDescription(TaskId("a"), "new body")

        assertEquals("""{"description":"old body"}""", outbox.enqueuedBefore.single())
    }

    @Test
    fun aWriteToAnAbsentRowCapturesNoBeforeImage() = runTest {
        val local = FakeTaskLocalStore() // empty
        val outbox = FakeOutboxStore()

        writer(local, outbox).rename(TaskId("ghost"), "renamed")

        assertEquals(null, outbox.enqueuedBefore.single()) // nothing cached → nothing to diff
    }

    @Test
    fun theOptimisticApplyReEmitsThroughTheObservedList() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to task("a", WorkingState.Open)))
        val outbox = FakeOutboxStore()
        val writer = writer(local, outbox)

        local.observeActive().test {
            assertEquals(WorkingState.Open, awaitItem().single().workingState)
            writer.setWorkingState(TaskId("a"), WorkingState.InProgress)
            assertEquals(WorkingState.InProgress, awaitItem().single().workingState)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
