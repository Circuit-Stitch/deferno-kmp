package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The replay engine (ADR-0001, #23), run against the in-memory fakes on the ADR-0006 JVM-fast path —
 * the heart of the issue. Covers FIFO replay, retry with backoff, strict head-of-line ordering, the
 * terminal-drop and max-attempts-exhaustion escape hatches, reconcile-only-after-a-successful-flush,
 * byte-identical idempotent replay, the backoff curve, the HTTP-status → outcome mapping, and the
 * flush-time occurrence compaction (#396) that runs before any of it.
 */
class OutboxProcessorTest {

    private val t0 = Instant.parse("2026-06-07T12:00:00Z")

    private fun req(name: String) = OutboxRequest(OutboxMethod.Patch, listOf("tasks", name), """{"title":"$name"}""")

    // One firing of one chore: `mark`/`clear` share a target, `on` picks a different firing (#396).
    private fun mark(action: OccurrenceAction = OccurrenceAction.Complete, on: Int = 8) =
        MarkOccurrence("ce-1", ItemKind.Chore, "cho-1-item", LocalDate(2026, 6, on), action)

    private fun clear(on: Int = 8) = ClearOccurrence("ce-1", ItemKind.Chore, "cho-1-item", LocalDate(2026, 6, on))

    private suspend fun OutboxStore.enqueue(mutation: OccurrenceMutation, now: Instant = t0) =
        enqueue(mutation.target, mutation.toRequest(), now)

    private fun processor(
        store: OutboxStore,
        sender: OutboxRequestSender,
        maxAttempts: Int = OutboxProcessor.DEFAULT_MAX_ATTEMPTS,
        reconcile: suspend () -> Unit = {},
    ) = OutboxProcessor(store, sender, reconcile, maxAttempts)

    // --- FIFO replay + reconcile ---

    @Test
    fun flushDispatchesInFifoOrderDeletesAndReconcilesOnce() = runTest {
        val store = FakeOutboxStore()
        store.enqueue("task:a", req("a"), t0)
        store.enqueue("task:b", req("b"), t0)
        store.enqueue("task:c", req("c"), t0)
        val sender = FakeOutboxRequestSender(SendOutcome.Success)
        var reconciles = 0

        val result = processor(store, sender, reconcile = { reconciles++ }).flush(t0)

        assertEquals(listOf("a", "b", "c"), sender.sent.map { it.path.last() }) // FIFO = enqueue order
        assertEquals(3, result.succeeded)
        assertEquals(0L, result.remaining)
        assertEquals(0L, store.count())
        assertEquals(1, reconciles) // exactly once, after the flush
    }

    @Test
    fun aDrainedQueueReFlushesAsANoOp() = runTest {
        val store = FakeOutboxStore()
        store.enqueue("task:a", req("a"), t0)
        val sender = FakeOutboxRequestSender(SendOutcome.Success)
        val processor = processor(store, sender)

        processor.flush(t0)
        val second = processor.flush(t0)

        // The entry was deleted on success, so the second flush dispatches nothing (no double-send).
        assertEquals(1, sender.sent.size)
        assertEquals(0, second.succeeded)
    }

    @Test
    fun reconcileDoesNotRunWhenNothingSucceeds() = runTest {
        val store = FakeOutboxStore()
        store.enqueue("task:a", req("a"), t0)
        store.enqueue("task:b", req("b"), t0)
        val sender = FakeOutboxRequestSender(SendOutcome.Terminal)
        var reconciles = 0

        val result = processor(store, sender, reconcile = { reconciles++ }).flush(t0)

        assertEquals(0, result.succeeded)
        assertEquals(2, result.dropped)
        assertEquals(0, reconciles) // a flush that only drops/retries has no new server state to pull
    }

    // --- retry + backoff + head-of-line ---

    @Test
    fun retryableHeadBacksOffAndBlocksTheQueue() = runTest {
        val store = FakeOutboxStore()
        store.enqueue("task:a", req("a"), t0)
        store.enqueue("task:b", req("b"), t0)
        val sender = FakeOutboxRequestSender(SendOutcome.Retryable)

        val result = processor(store, sender).flush(t0)

        assertEquals(1, result.retried)
        assertEquals(listOf("a"), sender.sent.map { it.path.last() }) // b is NOT sent — head-of-line
        val head = store.all.first()
        assertEquals(1, head.attempts)
        assertEquals(t0 + 1.seconds, head.nextAttemptAt) // backoff(1) == 1s
        assertEquals(2L, result.remaining)
    }

    @Test
    fun aBackedOffHeadIsSkippedUntilItsTimeThenRetried() = runTest {
        val store = FakeOutboxStore()
        store.enqueue("task:a", req("a"), t0)
        store.enqueue("task:b", req("b"), t0)
        val sender = FakeOutboxRequestSender(SendOutcome.Retryable)
        val processor = processor(store, sender)

        processor.flush(t0) // a -> retry, next at t0+1s
        val tooEarly = processor.flush(t0) // still before t0+1s: head not ready -> nothing dispatched
        assertEquals(0, tooEarly.retried)
        assertEquals(0, tooEarly.succeeded)
        assertEquals(1, sender.sent.size) // no new send

        sender.outcome = SendOutcome.Success
        val drained = processor.flush(t0 + 1.seconds) // a ready -> success, then b -> success
        assertEquals(2, drained.succeeded)
        assertEquals(0L, store.count())
        assertEquals(listOf("a", "a", "b"), sender.sent.map { it.path.last() }) // a re-sent, FIFO preserved
    }

    @Test
    fun terminalRejectionDropsTheHeadAndContinues() = runTest {
        val store = FakeOutboxStore()
        store.enqueue("task:a", req("a"), t0)
        store.enqueue("task:b", req("b"), t0)
        val sender = FakeOutboxRequestSender().apply {
            decide = { if (it.path.last() == "a") SendOutcome.Terminal else SendOutcome.Success }
        }

        val result = processor(store, sender).flush(t0)

        assertEquals(1, result.dropped)
        assertEquals(1, result.succeeded)
        assertEquals(0L, store.count()) // a dropped, b succeeded — the queue drains
        assertEquals(listOf("a", "b"), sender.sent.map { it.path.last() })
    }

    @Test
    fun anExhaustedHeadIsGivenUpOnSoItCannotStarveTheQueue() = runTest {
        val store = FakeOutboxStore()
        store.enqueue("task:a", req("a"), t0) // permanently flaky
        store.enqueue("task:b", req("b"), t0)
        val sender = FakeOutboxRequestSender().apply {
            decide = { if (it.path.last() == "a") SendOutcome.Retryable else SendOutcome.Success }
        }
        val processor = processor(store, sender, maxAttempts = 2)

        val first = processor.flush(t0) // a: attempts 0->1 (<2) -> retry+block
        assertEquals(1, first.retried)
        assertEquals(2L, store.count())

        val second = processor.flush(t0 + 1.seconds) // a: 1->2 (==max) -> dropped; then b -> success
        assertEquals(1, second.dropped)
        assertEquals(1, second.succeeded)
        assertEquals(0L, store.count()) // queue unblocked and drained
    }

    // --- idempotent replay ---

    @Test
    fun aRetriedEntryReplaysByteIdenticalBytes() = runTest {
        val store = FakeOutboxStore()
        val request = OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"complete_by":null}""")
        store.enqueue("task:a", request, t0)
        val sender = FakeOutboxRequestSender().apply { script = ArrayDeque(listOf(SendOutcome.Retryable, SendOutcome.Success)) }
        val processor = processor(store, sender)

        processor.flush(t0)
        processor.flush(t0 + 1.seconds)

        assertEquals(2, sender.sent.size)
        assertEquals(sender.sent[0], sender.sent[1]) // same request object/value re-sent
        assertTrue(sender.sent.all { it.body == """{"complete_by":null}""" }) // exact bytes preserved
    }

    // --- flush-time occurrence compaction (#396) ---

    @Test
    fun anOfflineMarkClearMarkOnOneFiringReplaysAsOneServerWrite() = runTest {
        // The issue's own case. Three offline intents on one firing; the flush dispatches ONE request,
        // and it is the final mark's bytes — the earlier two never touch the network.
        val store = FakeOutboxStore()
        store.enqueue(mark(OccurrenceAction.Complete))
        store.enqueue(clear())
        store.enqueue(mark(OccurrenceAction.Complete))
        val sender = FakeOutboxRequestSender(SendOutcome.Success)

        val result = processor(store, sender).flush(t0)

        assertEquals(1, sender.sent.size)
        assertEquals("""{"status":"done"}""", sender.sent.single().body)
        assertEquals(2, result.coalesced)
        assertEquals(1, result.succeeded)
        assertEquals(0L, store.count())
    }

    @Test
    fun compactingAwayABackedOffHeadUnblocksTheQueue() = runTest {
        // The compaction runs BEFORE the readiness break, so it sees the whole live queue rather than
        // its ready prefix. The superseded head is deleted, and the survivor dispatches on its own
        // attempt count instead of inheriting the penalty box the head was sitting in.
        val superseded = mark(OccurrenceAction.Start)
        val survivor = mark(OccurrenceAction.Complete)
        val store = FakeOutboxStore(
            listOf(
                OutboxEntry(1, superseded.target, superseded.toRequest(), 3, t0 + 5.minutes, t0),
                OutboxEntry(2, survivor.target, survivor.toRequest(), 0, t0, t0),
            ),
        )
        val sender = FakeOutboxRequestSender(SendOutcome.Success)

        val result = processor(store, sender).flush(t0)

        assertEquals(1, result.coalesced)
        assertEquals(1, result.succeeded)
        assertEquals("""{"status":"done"}""", sender.sent.single().body)
        assertEquals(0L, store.count())
    }

    @Test
    fun anInterleavedForeignEntryKeepsItsRelativePosition() = runTest {
        // The FIFO argument in one test: the pass only deletes, so the surviving entries replay in
        // exactly the order they would have. The task edit was enqueued between the two marks and still
        // goes out before the surviving mark.
        val store = FakeOutboxStore()
        store.enqueue(mark(OccurrenceAction.Start))
        store.enqueue("task:a", req("a"), t0)
        store.enqueue(mark(OccurrenceAction.Complete))
        val sender = FakeOutboxRequestSender(SendOutcome.Success)

        val result = processor(store, sender).flush(t0)

        assertEquals(1, result.coalesced)
        assertEquals(listOf("""{"title":"a"}""", """{"status":"done"}"""), sender.sent.map { it.body })
    }

    @Test
    fun aDeadLetteredOccurrenceEntryIsNeitherDeletedNorABarrier() = runTest {
        // Dead-lettered rows are preserved on purpose (PR #353) and can never reach the server, so the
        // compaction must not delete one — nor let one separate two live writes that CAN.
        val dead = mark(OccurrenceAction.Start)
        val store = FakeOutboxStore(listOf(OutboxEntry(1, dead.target, dead.toRequest(), 12, t0, t0, failedAt = t0)))
        store.enqueue(mark(OccurrenceAction.Skip))
        store.enqueue(mark(OccurrenceAction.Complete))
        val sender = FakeOutboxRequestSender(SendOutcome.Success)

        val result = processor(store, sender).flush(t0)

        // The two live marks collapsed around the dead row; the dead row is still in the queue.
        assertEquals(1, result.coalesced)
        assertEquals("""{"status":"done"}""", sender.sent.single().body)
        assertNotNull(store.all.single { it.seq == 1L }.failedAt)
    }

    @Test
    fun aQueueWithNothingSupersededIsUntouched() = runTest {
        // Two different firings and a task edit: nothing collapses, everything replays, `coalesced` is 0.
        val store = FakeOutboxStore()
        store.enqueue(mark(on = 8))
        store.enqueue("task:a", req("a"), t0)
        store.enqueue(mark(on = 9))
        val sender = FakeOutboxRequestSender(SendOutcome.Success)

        val result = processor(store, sender).flush(t0)

        assertEquals(0, result.coalesced)
        assertEquals(3, result.succeeded)
        assertEquals(3, sender.sent.size)
    }

    // --- backoff curve + status mapping (pure) ---

    @Test
    fun exponentialBackoffDoublesAndCapsAtFiveMinutes() {
        assertEquals(1.seconds, exponentialBackoff(1))
        assertEquals(2.seconds, exponentialBackoff(2))
        assertEquals(4.seconds, exponentialBackoff(3))
        assertEquals(256.seconds, exponentialBackoff(9))
        assertEquals(5.minutes, exponentialBackoff(10)) // 512s capped at 5m
        assertEquals(5.minutes, exponentialBackoff(100)) // stays capped
    }

    @Test
    fun statusMappingClassifiesOutcomes() {
        for (ok in listOf(200, 201, 204, 299, 404)) assertEquals(SendOutcome.Success, outcomeFor(ok), "$ok")
        for (retry in listOf(401, 408, 429, 500, 503, 599)) assertEquals(SendOutcome.Retryable, outcomeFor(retry), "$retry")
        for (terminal in listOf(400, 403, 409, 422, 302)) assertEquals(SendOutcome.Terminal, outcomeFor(terminal), "$terminal")
    }
}
