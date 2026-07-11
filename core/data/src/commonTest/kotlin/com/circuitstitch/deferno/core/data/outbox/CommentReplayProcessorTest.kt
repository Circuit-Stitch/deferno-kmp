package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.data.comment.DefaultCommentReplayListener
import com.circuitstitch.deferno.core.data.comment.FakeCommentLocalStore
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The comment-create replay route through the real [OutboxProcessor] + [DefaultCommentReplayListener]
 * (ADR-0043 "Comment-create idempotency") — the golden that pins the load-bearing behaviour: a queued
 * `create` then `edit` of the SAME just-posted comment must drain with the edit landing on the server's
 * id, never lost. Because the server never honours the client id, the create heal re-points the queued
 * edit and breaks the pass; the [OutboxDriver]'s flush-to-quiescence loop (modelled here) then drains it.
 */
class CommentReplayProcessorTest {

    private val t0 = Instant.parse("2026-06-21T12:00:00Z")
    private val task = TaskId("t-1")

    private fun scenario(): Triple<FakeCommentLocalStore, FakeOutboxStore, FakeOutboxRequestSender> {
        val store = FakeCommentLocalStore()
        val outbox = FakeOutboxStore()
        val sender = FakeOutboxRequestSender().apply {
            // The server mints a fresh id (it never honours the client id) — this drives the heal.
            createDecide = { CreateSendOutcome.Created(serverId = "server-1") }
        }
        return Triple(store, outbox, sender)
    }

    private suspend fun FakeCommentLocalStore.postThenEdit(outbox: FakeOutboxStore) {
        upsert(Comment("client", task, "hi", UserId("me"), t0))
        outbox.enqueue("comment-create:t-1:client", OutboxRequest(OutboxMethod.Post, listOf("tasks", "t-1", "comments"), """{"body":"hi"}"""), t0)
        outbox.enqueue("comment:client", OutboxRequest(OutboxMethod.Patch, listOf("comments", "client"), """{"body":"edited"}"""), t0)
    }

    @Test
    fun aSingleFlushBreaksAfterTheCreateHealSoTheQueuedEditIsRepointedBeforeItSends() = runTest {
        val (store, outbox, sender) = scenario()
        store.postThenEdit(outbox)
        val processor = OutboxProcessor(store = outbox, sender = sender, reconcile = {}, commentListener = DefaultCommentReplayListener(store, outbox))

        val result = processor.flush(t0)

        assertEquals(1, result.succeeded) // the create replayed
        assertEquals(1L, result.remaining) // the pass BROKE — the edit is still queued, not yet dispatched
        assertTrue(sender.sent.none { it.method == OutboxMethod.Patch }) // and was NOT sent against the dead client id
        // The queued edit was re-pointed in place to the server id, ready for the next pass.
        assertEquals(listOf("comments", "server-1"), outbox.all.single().request.path)
        assertEquals("server-1", store.all.single().id) // the optimistic row was rekeyed
    }

    @Test
    fun drivingToQuiescenceDrainsTheBurstWithTheEditLandingOnTheServerId() = runTest {
        val (store, outbox, sender) = scenario()
        store.postThenEdit(outbox)
        val processor = OutboxProcessor(store = outbox, sender = sender, reconcile = {}, commentListener = DefaultCommentReplayListener(store, outbox))

        // Mirror OutboxDriver.flushToQuiescence.
        var result: FlushResult
        do {
            result = processor.flush(t0)
        } while (result.succeeded > 0 && result.remaining > 0)

        assertEquals(0L, outbox.count()) // fully drained
        assertEquals("server-1", store.all.single().id)
        val patch = sender.sent.last { it.method == OutboxMethod.Patch }
        assertEquals(listOf("comments", "server-1"), patch.path) // the edit landed on the server id...
        assertTrue(sender.sent.none { it.method == OutboxMethod.Patch && it.path == listOf("comments", "client") }) // ...never the dead one
    }

    @Test
    fun aTerminallyRejectedCommentCreateIsDeadLetteredAndTheOptimisticPostSurvives() = runTest {
        // The exact "I posted a comment and 10s later it vanished" bug: the server terminally rejects the
        // create (e.g. a 422). The optimistic row MUST survive (never silently deleted) and the write MUST
        // be preserved for a later retry/discard — not dropped.
        val store = FakeCommentLocalStore().apply { upsert(Comment("client", task, "hi", UserId("me"), t0)) }
        val outbox = FakeOutboxStore().apply {
            enqueue("comment-create:t-1:client", OutboxRequest(OutboxMethod.Post, listOf("tasks", "t-1", "comments"), """{"body":"hi"}"""), t0)
        }
        val sender = FakeOutboxRequestSender().apply { createOutcome = CreateSendOutcome.Terminal }
        val processor = OutboxProcessor(store = outbox, sender = sender, reconcile = {}, commentListener = DefaultCommentReplayListener(store, outbox))

        val result = processor.flush(t0)

        assertEquals(1, result.dropped) // dead-lettered this pass
        assertEquals(0L, outbox.count()) // no LIVE work remains...
        assertEquals(t0, outbox.all.single().failedAt) // ...but the entry is PRESERVED, marked failed
        assertEquals("hi", store.all.single().body) // and the user's optimistic comment still shows

        // A later flush skips the dead-lettered entry — no retry loop, no second POST.
        val again = processor.flush(t0)
        assertEquals(0, again.dropped)
        assertEquals(1, sender.sent.size)
    }
}
