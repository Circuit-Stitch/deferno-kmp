package com.circuitstitch.deferno.core.data.outbox

import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The replay engine (ADR-0001, #23), run against the in-memory fakes on the ADR-0006 JVM-fast path —
 * the heart of the issue. Covers FIFO replay, retry with backoff, strict head-of-line ordering, the
 * terminal-drop and max-attempts-exhaustion escape hatches, reconcile-only-after-a-successful-flush,
 * byte-identical idempotent replay, the backoff curve, and the HTTP-status → outcome mapping.
 */
class OutboxProcessorTest {

    private val t0 = Instant.parse("2026-06-07T12:00:00Z")

    private fun req(name: String) = OutboxRequest(OutboxMethod.Patch, listOf("tasks", name), """{"title":"$name"}""")

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
