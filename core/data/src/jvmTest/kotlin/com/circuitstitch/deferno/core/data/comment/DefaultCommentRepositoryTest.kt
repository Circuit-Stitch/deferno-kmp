package com.circuitstitch.deferno.core.data.comment

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.outbox.CommentTargets
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The outbox-aware reconcile of [DefaultCommentRepository] (ADR-0043, #197) over a real in-memory
 * `DefernoDatabase` (ADR-0006 JVM-fast path). Proves the offline-first contract: reads come from the
 * cache, a refresh upserts server rows and drops server-gone ones, an [RemoteSnapshot.Unavailable] pull
 * leaves the cache intact, and — the #143 clobber-guard — a comment with an un-synced outbox mutation (an
 * optimistic edit/delete or a create, live OR dead-lettered) survives a refresh that would otherwise revert
 * it. The guard reads the full `allUnsynced()` view precisely so a dead-lettered write still protects (#353).
 */
class DefaultCommentRepositoryTest {

    private val t0 = Instant.parse("2026-06-21T12:00:00Z")
    private val t1 = Instant.parse("2026-06-21T12:00:01Z")
    private val task = TaskId("t-1")

    private fun newStore() = SqlDelightCommentLocalStore(
        DefernoDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) }),
        Dispatchers.Unconfined,
    )

    private fun comment(id: String, body: String? = "hi", at: Instant = t0) =
        Comment(id = id, taskId = task, body = body, createdBy = UserId("u-1"), createdAt = at)

    private class FakeRemote(var snapshot: RemoteSnapshot<List<Comment>>) : CommentRemoteSource {
        override suspend fun fetchComments(taskId: TaskId) = snapshot
    }

    @Test
    fun observeReadsFromTheCacheNotTheNetwork() = runTest {
        val store = newStore()
        store.upsert(comment("c-1"))
        val repo = DefaultCommentRepository(store, FakeRemote(RemoteSnapshot.Unavailable), FakeOutboxStore())

        assertEquals(listOf("c-1"), repo.observe(task).first().map { it.id })
    }

    @Test
    fun refreshUpsertsServerComments() = runTest {
        val store = newStore()
        val remote = FakeRemote(RemoteSnapshot.Available(listOf(comment("c-1", at = t0), comment("c-2", at = t1))))
        val repo = DefaultCommentRepository(store, remote, FakeOutboxStore())

        repo.refresh(task)

        assertEquals(listOf("c-1", "c-2"), store.observe(task).first().map { it.id })
    }

    @Test
    fun refreshDropsCommentsTheServerNoLongerHas() = runTest {
        val store = newStore()
        store.upsert(comment("c-1"))
        store.upsert(comment("c-2"))
        // Server only returns c-1 now (c-2 was deleted server-side).
        val repo = DefaultCommentRepository(store, FakeRemote(RemoteSnapshot.Available(listOf(comment("c-1")))), FakeOutboxStore())

        repo.refresh(task)

        assertEquals(listOf("c-1"), store.observe(task).first().map { it.id })
    }

    @Test
    fun refreshNeverClobbersAPendingEdit() = runTest {
        val store = newStore()
        store.upsert(comment("c-1", body = "server"))
        store.setBody("c-1", "my optimistic edit", t1) // optimistic local edit
        val outbox = FakeOutboxStore().apply { enqueue(CommentTargets.edit("c-1"), patch(), t1) }
        // Server still has the pre-edit body — the refresh must NOT overwrite the optimistic edit.
        val repo = DefaultCommentRepository(store, FakeRemote(RemoteSnapshot.Available(listOf(comment("c-1", body = "server")))), outbox)

        repo.refresh(task)

        assertEquals("my optimistic edit", store.observe(task).first().single().body)
    }

    @Test
    fun refreshKeepsAnUnsyncedPostedComment() = runTest {
        val store = newStore()
        store.upsert(comment("client-uuid")) // optimistic post, not yet on the server
        val outbox = FakeOutboxStore().apply { enqueue(CommentTargets.create("t-1", "client-uuid"), post(), t0) }
        // Server thread is empty (the create hasn't replayed) — the un-synced comment must survive.
        val repo = DefaultCommentRepository(store, FakeRemote(RemoteSnapshot.Available(emptyList())), outbox)

        repo.refresh(task)

        assertEquals(listOf("client-uuid"), store.observe(task).first().map { it.id })
    }

    @Test
    fun refreshKeepsADeadLetteredPostedComment() = runTest {
        // PR #353: a terminally-rejected comment create is dead-lettered (never landed server-side, never
        // deleted locally). The clobber-guard reads the FULL queue (allUnsynced, incl. dead-lettered rows),
        // so the optimistic post must STILL survive a refresh — the exact "comment vanished ~10s later" bug,
        // now guarded even after the write gives up. (A live create is covered by the test above; this pins
        // that dropping to the syncable view here would silently resurrect the bug.)
        val store = newStore()
        store.upsert(comment("client-uuid"))
        val outbox = FakeOutboxStore().apply {
            enqueue(CommentTargets.create("t-1", "client-uuid"), post(), t0)
            markFailed(allUnsynced().single().seq, t1) // the server terminally rejected the create → dead-lettered
        }
        val repo = DefaultCommentRepository(store, FakeRemote(RemoteSnapshot.Available(emptyList())), outbox)

        repo.refresh(task)

        assertEquals(listOf("client-uuid"), store.observe(task).first().map { it.id })
    }

    @Test
    fun refreshKeepsAPendingLocalTombstone() = runTest {
        val store = newStore()
        store.upsert(comment("c-1"))
        store.softDelete("c-1", t1) // optimistic delete
        val outbox = FakeOutboxStore().apply { enqueue(CommentTargets.edit("c-1"), OutboxRequest(OutboxMethod.Delete, listOf("comments", "c-1")), t1) }
        // Server still returns c-1 (the delete hasn't replayed) — the refresh must NOT resurrect it.
        val repo = DefaultCommentRepository(store, FakeRemote(RemoteSnapshot.Available(listOf(comment("c-1")))), outbox)

        repo.refresh(task)

        assertEquals(emptyList(), store.observe(task).first().map { it.id })
    }

    @Test
    fun refreshLeavesTheCacheIntactWhenUnavailable() = runTest {
        val store = newStore()
        store.upsert(comment("c-1"))
        val repo = DefaultCommentRepository(store, FakeRemote(RemoteSnapshot.Unavailable), FakeOutboxStore())

        repo.refresh(task)

        assertEquals(listOf("c-1"), store.observe(task).first().map { it.id })
    }

    private fun patch() = OutboxRequest(OutboxMethod.Patch, listOf("comments", "c-1"), "{\"body\":\"x\"}")
    private fun post() = OutboxRequest(OutboxMethod.Post, listOf("tasks", "t-1", "comments"), "{\"body\":\"x\"}")
}
