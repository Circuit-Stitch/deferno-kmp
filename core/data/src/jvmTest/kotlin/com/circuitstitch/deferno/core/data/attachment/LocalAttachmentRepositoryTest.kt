package com.circuitstitch.deferno.core.data.attachment

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The real-SQLite integration test for the on-device [LocalAttachmentRepository] (#210, ADR-0006 JVM-fast
 * path): proves the SQL<->domain round-trip (incl. nullable task_id/caption + the `size` INTEGER), that the
 * provider + locator are recorded and queryable (acceptance #2), and that bytes round-trip entirely
 * on-device with no network (acceptance #1) — through a genuine `DefernoDatabase` over an in-memory
 * `JdbcSqliteDriver` plus an in-memory byte store. Timestamps are injected (`Instant.parse`) — never `Clock.System`.
 */
class LocalAttachmentRepositoryTest {

    private val created = Instant.parse("2026-06-15T10:00:00Z")

    private fun newRepo(store: AttachmentBytesStore = InMemoryAttachmentBytesStore()): LocalAttachmentRepository {
        val db = DefernoDatabase(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
        )
        return LocalAttachmentRepository(db, store)
    }

    @Test
    fun saveRecordsProviderAndLocatorAndRoundTrips() = runTest {
        val repo = newRepo()
        val saved = repo.save(
            id = "att-1",
            taskId = "task-9",
            filename = "note.txt",
            mime = "text/plain",
            bytes = "hello".encodeToByteArray(),
            createdAt = created,
        )

        assertEquals(StorageProviderId.OnDevice, saved.provider)
        assertEquals("att-1", saved.locator)
        assertEquals(5L, saved.size)
        assertEquals("task-9", saved.taskId)
        assertNull(saved.caption)

        // The full record is queryable, provider + locator included (acceptance #2).
        assertEquals(saved, repo.get("att-1"))
    }

    @Test
    fun bytesAreStoredAndReopenedEntirelyOnDevice() = runTest {
        val repo = newRepo()
        repo.save("att-1", null, "a.bin", "application/octet-stream", byteArrayOf(7, 8, 9), created)

        // Re-opened with no network — the bytes come straight from on-device storage (acceptance #1).
        assertContentEquals(byteArrayOf(7, 8, 9), repo.bytes("att-1"))
    }

    @Test
    fun forTaskReturnsOnlyThatTasksAttachmentsNewestFirst() = runTest {
        val repo = newRepo()
        repo.save("old", "task-1", "1", "text/plain", byteArrayOf(1), Instant.parse("2026-06-01T00:00:00Z"))
        repo.save("new", "task-1", "2", "text/plain", byteArrayOf(2), Instant.parse("2026-06-10T00:00:00Z"))
        repo.save("other", "task-2", "3", "text/plain", byteArrayOf(3), created)

        assertEquals(listOf("new", "old"), repo.forTask("task-1").map { it.id })
    }

    @Test
    fun deleteRemovesRecordAndBytes() = runTest {
        val store = InMemoryAttachmentBytesStore()
        val repo = newRepo(store)
        repo.save("att-1", null, "a", "text/plain", byteArrayOf(1), created)

        repo.delete("att-1")

        assertNull(repo.get("att-1"))
        assertNull(repo.bytes("att-1"))
        assertNull(store.read("att-1"))
    }

    @Test
    fun bytesForUnknownAttachmentIsNull() = runTest {
        assertNull(newRepo().bytes("nope"))
    }

    @Test
    fun observeBrainDumpRecordingsReturnsOnlyRecordingsLargestFirst() = runTest {
        val repo = newRepo()
        // Two brain-dump recordings (an un-triaged placeholder + one attached to a Task) and one ordinary
        // on-device attachment — only the `braindump`-prefixed rows are recordings (#211).
        repo.save("braindump-audio-1", null, "brain-dump-1.wav", "audio/wav", ByteArray(300), created)
        repo.save("braindump:task-9", "task-9", "brain-dump-2.wav", "audio/wav", ByteArray(900), created)
        repo.save("att-1", "task-9", "note.txt", "text/plain", ByteArray(50), created)

        val recordings = repo.observeBrainDumpRecordings().first()

        // Recordings only (the text attachment is excluded), largest first.
        assertEquals(listOf("braindump:task-9", "braindump-audio-1"), recordings.map { it.id })
        assertEquals(listOf(900L, 300L), recordings.map { it.size })
    }

    @Test
    fun rekeyTaskMovesAttachmentsToTheCanonicalIdAndLeavesOthers() = runTest {
        // gh#223 / #185 id-heal: a brain-dump attachment saved under the client id must follow the Task to
        // its server canonical id, or forTask(canonical) finds nothing. Re-keys task_id only — the row id stays.
        val repo = newRepo()
        repo.save("braindump:client", "client", "dump.wav", "audio/wav", byteArrayOf(1), created)
        repo.save("other", "task-2", "x.txt", "text/plain", byteArrayOf(2), created)

        repo.rekeyTask(from = "client", to = "server")

        assertEquals(emptyList(), repo.forTask("client").map { it.id })
        assertEquals(listOf("braindump:client"), repo.forTask("server").map { it.id }) // row id unchanged
        assertEquals("server", repo.get("braindump:client")?.taskId)
        assertEquals(listOf("other"), repo.forTask("task-2").map { it.id }) // unrelated row untouched
    }
}
