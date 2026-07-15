package com.circuitstitch.deferno.core.data.comment

import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The offline-first comment write path (ADR-0043): every write applies optimistically to the cache
 * (so the observed thread updates with no network) and enqueues its replayable mutation. Proven over the
 * in-memory [FakeCommentLocalStore] + [FakeOutboxStore] on the JVM-fast path (ADR-0006).
 */
class OutboxCommentWriterTest {

    private val t0 = Instant.parse("2026-06-21T12:00:00Z")
    private val task = TaskId("t-1")

    private fun writer(store: CommentLocalStore, outbox: FakeOutboxStore, id: String = "c-new") =
        OutboxCommentWriter(store, outbox, author = { UserId("me") }, now = { t0 }, newId = { id })

    @Test
    fun postInsertsTheOptimisticCommentAsMineAndEnqueuesTheCreate() = runTest {
        val store = FakeCommentLocalStore()
        val outbox = FakeOutboxStore()

        writer(store, outbox, id = "c-new").post(task, "hi")

        val comment = store.observe(task).first().single()
        assertEquals("c-new", comment.id)
        assertEquals(UserId("me"), comment.createdBy) // stamped mine → "You" + edit/delete affordances offline
        assertEquals("hi", comment.body)
        val entry = outbox.all.single()
        assertEquals("comment-create:t-1:c-new", entry.target)
        assertEquals(OutboxMethod.Post, entry.request.method)
    }

    @Test
    fun editAppliesTheOptimisticBodyAndEnqueuesTheIdempotentPatch() = runTest {
        val store = FakeCommentLocalStore().apply { upsert(Comment("c-1", task, "old", UserId("me"), t0)) }
        val outbox = FakeOutboxStore()

        writer(store, outbox).edit("c-1", "new")

        assertEquals("new", store.observe(task).first().single().body)
        // The target now tags the comment's task (resolved from the store) so the Activity feed can
        // resolve which item the edit touched (#260).
        assertEquals("comment:t-1:c-1", outbox.all.single().target)
        assertEquals(OutboxMethod.Patch, outbox.all.single().request.method)
    }

    @Test
    fun deleteTombstonesOptimisticallyAndEnqueuesTheIdempotentDelete() = runTest {
        val store = FakeCommentLocalStore().apply { upsert(Comment("c-1", task, "x", UserId("me"), t0)) }
        val outbox = FakeOutboxStore()

        writer(store, outbox).delete("c-1")

        assertEquals(emptyList(), store.observe(task).first().map { it.id }) // gone from the live thread
        assertEquals("comment:t-1:c-1", outbox.all.single().target)
        assertEquals(OutboxMethod.Delete, outbox.all.single().request.method)
    }
}
