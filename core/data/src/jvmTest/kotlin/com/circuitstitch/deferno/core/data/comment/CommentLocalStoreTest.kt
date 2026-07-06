package com.circuitstitch.deferno.core.data.comment

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
 * The offline-first comment cache store (ADR-0043, #197): proves the SQLDelight `commentEntity` CRUD
 * that backs the observed thread over a real in-memory `DefernoDatabase` (ADR-0006 JVM-fast path) —
 * the live thread excludes soft-deleted rows and is oldest-first, optimistic edit/delete apply
 * in-place, and a client id re-keys to a server id on create replay.
 */
class CommentLocalStoreTest {

    private val t0 = Instant.parse("2026-06-21T12:00:00Z")
    private val t1 = Instant.parse("2026-06-21T12:00:01Z")

    private fun newStore(): CommentLocalStore = SqlDelightCommentLocalStore(
        DefernoDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) }),
        Dispatchers.Unconfined,
    )

    private fun comment(id: String, task: String = "task-1", at: Instant = t0, body: String? = "hi") = Comment(
        id = id, taskId = TaskId(task), body = body, createdBy = UserId("u-1"), createdAt = at,
    )

    @Test
    fun upsertedCommentIsObservedOnItsTaskThread() = runTest {
        val store = newStore()

        store.upsert(comment("c-1"))

        assertEquals(listOf("c-1"), store.observe(TaskId("task-1")).first().map { it.id })
    }

    @Test
    fun theThreadIsOldestFirstAndScopedToTheTask() = runTest {
        val store = newStore()
        store.upsert(comment("c-2", at = t1))
        store.upsert(comment("c-1", at = t0))
        store.upsert(comment("other", task = "task-2", at = t0))

        assertEquals(listOf("c-1", "c-2"), store.observe(TaskId("task-1")).first().map { it.id })
    }

    @Test
    fun aSoftDeletedCommentLeavesTheLiveThread() = runTest {
        val store = newStore()
        store.upsert(comment("c-1"))

        store.softDelete("c-1", t1)

        assertEquals(emptyList(), store.observe(TaskId("task-1")).first())
    }

    @Test
    fun editSwapsTheBodyAndStampsEditedAt() = runTest {
        val store = newStore()
        store.upsert(comment("c-1", body = "old"))

        store.setBody("c-1", "new", t1)

        val edited = store.observe(TaskId("task-1")).first().single()
        assertEquals("new", edited.body)
        assertEquals(t1, edited.editedAt)
    }

    @Test
    fun rekeyMovesTheRowFromClientIdToServerId() = runTest {
        val store = newStore()
        store.upsert(comment("client-uuid"))

        store.rekey(fromId = "client-uuid", toId = "server-99")

        assertEquals(listOf("server-99"), store.observe(TaskId("task-1")).first().map { it.id })
        assertEquals(listOf("server-99"), store.idsForTask(TaskId("task-1")))
    }

    @Test
    fun deleteByIdHardRemovesAnOptimisticRow() = runTest {
        val store = newStore()
        store.upsert(comment("c-1"))

        store.deleteById("c-1")

        assertEquals(emptyList(), store.idsForTask(TaskId("task-1")))
    }
}
