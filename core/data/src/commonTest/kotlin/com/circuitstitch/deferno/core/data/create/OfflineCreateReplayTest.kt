package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.outbox.CreateSendOutcome
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxRequestSender
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxProcessor
import com.circuitstitch.deferno.core.data.plan.FakePlanLocalStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The offline create → replay → confirm/heal/reject loop end to end (#185), wiring the real
 * [OfflineCreateWriter] → outbox → [OutboxProcessor] → [DefaultCreateReplayListener] on the in-memory
 * fakes (ADR-0006 JVM-fast path). Proves: a replay the server honors confirms the pending row and does
 * not duplicate; a replay the server re-ids heals the local references; a terminal rejection dead-letters
 * the entry and **preserves** the optimistic insert (never silently deletes the user's create).
 */
class OfflineCreateReplayTest {

    private val t0 = Instant.parse("2026-06-07T12:00:00Z")

    private class Fixture(clientId: String = "client-1") {
        val taskStore = FakeTaskLocalStore()
        val habitStore = FakeHabitLocalStore()
        val choreStore = FakeChoreLocalStore()
        val eventStore = FakeEventLocalStore()
        val planStore = FakePlanLocalStore()
        val outbox = FakeOutboxStore()
        val pending = FakePendingCreateStore()
        val sender = FakeOutboxRequestSender()
        val writer = OfflineCreateWriter(
            connectivity = FakeConnectivity(online = false),
            remoteSource = FakeItemRemoteSource(),
            taskStore = taskStore,
            habitStore = habitStore,
            choreStore = choreStore,
            eventStore = eventStore,
            outbox = outbox,
            pendingCreateStore = pending,
            newId = { clientId },
            now = { Instant.parse("2026-06-07T12:00:00Z") },
            orgSlug = { "u-test" },
        )
        val healer = ItemIdHealer(taskStore, habitStore, choreStore, eventStore, planStore, outbox)
        val listener = DefaultCreateReplayListener(healer, pending)
        val processor = OutboxProcessor(outbox, sender, reconcile = {}, createListener = listener)
    }

    @Test
    fun replayServerHonorsClientId_confirmsAndDoesNotDuplicate() = runTest {
        val f = Fixture()
        f.writer.createTask(CreateTaskPayload(title = "buy milk"))
        f.sender.createOutcome = CreateSendOutcome.Created(serverId = "client-1") // honored

        val result = f.processor.flush(t0)

        assertEquals(1, result.succeeded)
        assertEquals(0L, f.outbox.count()) // create entry drained, no re-send
        // Local row stays under the same id; the pending row is now confirmed.
        assertEquals(setOf(TaskId("client-1")), f.taskStore.all.keys)
        val pending = f.pending.all.single()
        assertEquals(PendingCreateState.Confirmed, pending.state)
        assertEquals("client-1", pending.canonicalId)
    }

    @Test
    fun replayServerReturnsDifferentId_healsLocalReferences() = runTest {
        val f = Fixture()
        f.writer.createTask(CreateTaskPayload(title = "buy milk"))
        f.sender.createOutcome = CreateSendOutcome.Created(serverId = "server-9") // diverged

        val result = f.processor.flush(t0)

        assertEquals(1, result.succeeded)
        // The Item row was re-keyed to the server's canonical id.
        assertNull(f.taskStore.all[TaskId("client-1")])
        assertTrue(f.taskStore.all.containsKey(TaskId("server-9")))
        // The pending row moved to the canonical id and is confirmed.
        val pending = f.pending.all.single()
        assertEquals("server-9", pending.itemId)
        assertEquals(PendingCreateState.Confirmed, pending.state)
    }

    @Test
    fun terminalRejectionDeadLettersAndPreservesTheOptimisticInsert() = runTest {
        val f = Fixture()
        f.writer.createTask(CreateTaskPayload(title = ""))
        f.sender.createOutcome = CreateSendOutcome.Terminal // server rejected the create (e.g. 422)

        val result = f.processor.flush(t0)

        assertEquals(1, result.dropped) // dead-lettered this pass
        // The entry is PRESERVED (dead-lettered, not deleted) — the user's create is never silently lost.
        val entry = f.outbox.all.single()
        assertEquals(t0, entry.failedAt)
        // The optimistic Item row + its pending-create purge-protection survive (still there, still Pending).
        assertTrue(f.taskStore.all.containsKey(TaskId("client-1")))
        assertEquals(PendingCreateState.Pending, f.pending.all.single().state)

        // A later flush skips the dead-lettered entry — no retry loop, no second send to the server.
        val again = f.processor.flush(t0)
        assertEquals(0, again.succeeded)
        assertEquals(0, again.dropped)
        assertEquals(1, f.sender.sent.size) // still just the one original send
    }
}
