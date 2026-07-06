package com.circuitstitch.deferno.backup

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import com.circuitstitch.deferno.core.data.attachment.FileAttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.LocalAttachmentRepository
import com.circuitstitch.deferno.core.data.backup.BackupExporter
import com.circuitstitch.deferno.core.data.backup.BackupImporter
import com.circuitstitch.deferno.core.data.backup.ImportResult
import com.circuitstitch.deferno.core.data.chore.SqlDelightChoreLocalStore
import com.circuitstitch.deferno.core.data.create.SqlDelightPendingCreateStore
import com.circuitstitch.deferno.core.data.event.SqlDelightEventLocalStore
import com.circuitstitch.deferno.core.data.habit.SqlDelightHabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.SqlDelightOutboxStore
import com.circuitstitch.deferno.core.data.task.SqlDelightTaskLocalStore
import com.circuitstitch.deferno.core.database.databaseFileName
import com.circuitstitch.deferno.core.database.driver.AndroidSqlDriverFactory
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Instant

/**
 * On-device round-trip for #315 (PR #342): export a Task's kept on-device attachment into the Backup zip,
 * then import it into a **freshly wiped** account and confirm the recording's bytes come back byte-identical.
 * Unlike the commonMain jvmTest (in-memory SQLite + in-memory byte store), this runs on the device against
 * the REAL Android infra the import half has no UI to reach: a SQLCipher-encrypted [DefernoDatabase] and the
 * filesystem-backed [FileAttachmentBytesStore]. Source and destination are two separate encrypted DB files +
 * byte dirs, so the restore is proven to reconstruct everything from the zip alone.
 */
class BackupAttachmentsRoundTripInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    // A fixed test passphrase — still exercises real SQLCipher; no Keystore vault needed for the round-trip.
    private val keyProvider = com.circuitstitch.deferno.core.database.DatabaseKeyProvider { "backup-roundtrip-test-key" }
    private val srcAccount = AccountId("backup-rt-src")
    private val dstAccount = AccountId("backup-rt-dst")
    private val srcBytesDir = File(ctx.cacheDir, "backup-rt-src-attach")
    private val dstBytesDir = File(ctx.cacheDir, "backup-rt-dst-attach")

    private val created = Instant.parse("2026-05-20T16:11:42Z")
    private val audio = ByteArray(64 * 1024) { (it * 31 + 7).toByte() } // high-bit bytes → CRC/binary fidelity

    // Every opened driver is tracked so @After can close it before the file is deleted (an open SQLCipher
    // connection outliving its file is what strands -wal/-shm sidecars on a repeated connectedAndroidTest run).
    private val drivers = mutableListOf<SqlDriver>()

    private fun db(account: AccountId) =
        DefernoDatabase(AndroidSqlDriverFactory(ctx, account, keyProvider).create().also { drivers += it })

    @After
    fun cleanup() {
        drivers.forEach { it.close() }
        drivers.clear()
        srcBytesDir.deleteRecursively()
        dstBytesDir.deleteRecursively()
        // deleteDatabase removes the main file plus its -wal/-shm/-journal sidecars in one call.
        ctx.deleteDatabase(databaseFileName(srcAccount))
        ctx.deleteDatabase(databaseFileName(dstAccount))
    }

    @Test
    fun exportedOnDeviceAttachment_restoresByteIdentical_intoWipedAccount() = runBlocking {
        // Start clean too (symmetric with @After), so an aborted prior run can't leave a stale src DB behind.
        srcBytesDir.deleteRecursively(); dstBytesDir.deleteRecursively()
        ctx.deleteDatabase(databaseFileName(srcAccount)); ctx.deleteDatabase(databaseFileName(dstAccount))

        // --- Source account: a Task with one kept on-device recording linked to it. ---
        val srcDb = db(srcAccount)
        val srcRepo = LocalAttachmentRepository(srcDb, FileAttachmentBytesStore(srcBytesDir))
        val srcTasks = SqlDelightTaskLocalStore(srcDb)
        val task = Task(
            id = TaskId("task-rt-1"),
            orgSlug = "u-e4h2qk",
            title = "Buy milk and bread",
            workingState = WorkingState.Open,
            dateCreated = created,
            hydration = HydrationState.Full,
            description = "from a brain dump",
        )
        srcTasks.upsert(task)
        srcRepo.save(
            id = "att-rt-1",
            taskId = task.id.value,
            filename = "brain-dump.wav",
            mime = "audio/wav",
            bytes = audio,
            createdAt = created,
            caption = "morning note",
        )

        val zip = BackupExporter(
            taskStore = srcTasks,
            habitStore = SqlDelightHabitLocalStore(srcDb),
            choreStore = SqlDelightChoreLocalStore(srcDb),
            eventStore = SqlDelightEventLocalStore(srcDb),
            localAttachments = srcRepo,
        ).buildBackupZip()
        assertTrue("zip should carry the embedded attachment bytes", zip.size > audio.size)

        // --- Destination account: a fresh, empty encrypted DB + byte dir (the "wipe"). ---
        val dstDb = db(dstAccount)
        val dstRepo = LocalAttachmentRepository(dstDb, FileAttachmentBytesStore(dstBytesDir))
        assertTrue("destination starts empty", dstRepo.forTask(task.id.value).isEmpty())

        val result = BackupImporter(
            taskStore = SqlDelightTaskLocalStore(dstDb),
            habitStore = SqlDelightHabitLocalStore(dstDb),
            choreStore = SqlDelightChoreLocalStore(dstDb),
            eventStore = SqlDelightEventLocalStore(dstDb),
            outbox = SqlDelightOutboxStore(dstDb),
            pendingCreateStore = SqlDelightPendingCreateStore(dstDb),
            localAttachments = dstRepo,
        ).import(zip)

        assertEquals(ImportResult.Restored(1), result)

        // The recording is re-linked to the restored Task, with its caption + bytes intact.
        val restored = dstRepo.forTask(task.id.value)
        assertEquals("one attachment restored", 1, restored.size)
        assertEquals("att-rt-1", restored[0].id)
        assertEquals("morning note", restored[0].caption)
        assertEquals(audio.size.toLong(), restored[0].size)
        assertArrayEquals("restored bytes must be byte-identical", audio, dstRepo.bytes("att-rt-1"))
    }
}
