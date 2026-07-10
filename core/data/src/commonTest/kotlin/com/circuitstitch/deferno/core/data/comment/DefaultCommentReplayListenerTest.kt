package com.circuitstitch.deferno.core.data.comment

import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The comment-create replay heal (ADR-0043): on replay the server assigns a fresh id (it never honours
 * the client id), so the listener rekeys the optimistic row and re-points any queued edit/delete — and
 * reports `healed` so the processor breaks its now-stale pass.
 */
class DefaultCommentReplayListenerTest {

    private val t0 = Instant.parse("2026-06-21T12:00:00Z")
    private val task = TaskId("t-1")

    private fun comment(id: String) = Comment(id, task, "hi", UserId("me"), t0)

    @Test
    fun rekeysTheOptimisticRowAndReportsNotHealedWhenNothingWasQueued() = runTest {
        val store = FakeCommentLocalStore().apply { upsert(comment("client")) }
        val listener = DefaultCommentReplayListener(store, FakeOutboxStore())

        val healed = listener.onReplayed(task.value, "client", "server-1")

        assertEquals("server-1", store.all.single().id)
        assertFalse(healed) // no queued comment:<client> edit/delete → the outbox is unchanged, pass may continue
    }

    @Test
    fun repointsAQueuedEditToTheServerIdAndReportsHealed() = runTest {
        val store = FakeCommentLocalStore().apply { upsert(comment("client")) }
        val outbox = FakeOutboxStore().apply {
            enqueue("comment:client", OutboxRequest(OutboxMethod.Patch, listOf("comments", "client"), """{"body":"x"}"""), t0)
        }
        val listener = DefaultCommentReplayListener(store, outbox)

        val healed = listener.onReplayed(task.value, "client", "server-1")

        assertTrue(healed)
        val entry = outbox.all.single()
        assertEquals("comment:server-1", entry.target)
        assertEquals(listOf("comments", "server-1"), entry.request.path)
    }

    @Test
    fun anEchoedIdIsANoOp() = runTest {
        val store = FakeCommentLocalStore().apply { upsert(comment("client")) }

        val healed = DefaultCommentReplayListener(store, FakeOutboxStore()).onReplayed(task.value, "client", "client")

        assertEquals("client", store.all.single().id)
        assertFalse(healed)
    }

    // A terminal rejection no longer undoes the optimistic post — the processor dead-letters it and the
    // row is preserved (the user's comment must never silently vanish). Covered at the processor level in
    // CommentReplayProcessorTest; there is no onRejected on the listener anymore.
}
