package com.circuitstitch.deferno.core.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.data.attachment.AttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.InMemoryAttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.LocalAttachmentRepository
import com.circuitstitch.deferno.core.data.attachment.StorageProviderId
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.Envelope
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.LocalAttachmentDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * On-device attachment bytes in the Backup file (#315, ADR-0041): export embeds each kept brain-dump
 * recording's raw bytes at `attachments/<id>` with its metadata nested under the owning Task, and import
 * restores them as **local on-device attachments** re-linked to the item — offline, no network. Proved
 * against a **real** [LocalAttachmentRepository] over an in-memory `JdbcSqliteDriver` (ADR-0006 JVM-fast
 * path, the same idiom as `LocalAttachmentRepositoryTest`); the item spine reuses the commonTest store
 * fakes. The pure items-only paths stay in the commonTest exporter/importer suites.
 */
class BackupAttachmentsJvmTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun newRepo(store: AttachmentBytesStore = InMemoryAttachmentBytesStore()) = LocalAttachmentRepository(
        DefernoDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) }),
        store,
    )

    private fun task(id: String) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = "task-$id",
        workingState = WorkingState.Open,
        dateCreated = created,
        hydration = HydrationState.Full,
        description = "desc-$id",
    )

    private fun exporter(tasks: List<Task>, repo: LocalAttachmentRepository) = BackupExporter(
        taskStore = FakeTaskLocalStore(tasks.associateBy { it.id }),
        habitStore = FakeHabitLocalStore(),
        choreStore = FakeChoreLocalStore(),
        eventStore = FakeEventLocalStore(),
        localAttachments = repo,
    )

    private class ImportFixture(repo: LocalAttachmentRepository) {
        val taskStore = FakeTaskLocalStore()
        val outbox = FakeOutboxStore()
        val importer = BackupImporter(
            taskStore, FakeHabitLocalStore(), FakeChoreLocalStore(), FakeEventLocalStore(),
            outbox, FakePendingCreateStore(), repo,
            now = { Instant.parse("2026-06-28T00:00:00Z") },
        )
    }

    @Test
    fun export_embeds_attachment_bytes_and_nests_api_shaped_metadata() = runTest {
        val repo = newRepo()
        val audio = byteArrayOf(0, 1, 2, 3, 4, 5)
        repo.save("braindump:t1", "t1", "brain-dump.wav", "audio/wav", audio, created, caption = "morning idea")

        val zip = exporter(listOf(task("t1")), repo).buildBackupZip()
        val entries = unzipStored(zip)

        // AC: raw bytes land at attachments/<attachmentId>, and ONLY the on-device attachment is embedded —
        // no backend-hosted blob was fetched (the exporter has no network; offline-pure).
        assertEquals(setOf("items.json", "attachments/braindump:t1"), entries.keys)
        assertContentEquals(audio, entries["attachments/braindump:t1"])

        // AC: metadata nested per item in the manifest, in the API's snake_case attachment shape.
        val itemsJson = entries["items.json"]!!.decodeToString()
        assertTrue(itemsJson.contains("\"local_attachments\""), itemsJson)
        assertTrue(itemsJson.contains("\"created_at\""), itemsJson)
        val att = decodeTasks(itemsJson).single().localAttachments.single()
        assertEquals(
            LocalAttachmentDto("braindump:t1", "brain-dump.wav", "audio/wav", 6L, "morning idea", created.toString()),
            att,
        )
    }

    @Test
    fun a_task_with_no_on_device_attachments_nests_nothing() = runTest {
        val zip = exporter(listOf(task("t1")), newRepo()).buildBackupZip()
        val entries = unzipStored(zip)

        assertEquals(setOf("items.json"), entries.keys) // no attachments/ entry
        assertTrue(decodeTasks(entries["items.json"]!!.decodeToString()).single().localAttachments.isEmpty())
    }

    @Test
    fun binary_attachment_bytes_survive_the_hand_rolled_zip_crc() = runTest {
        // High-bit bytes stress the STORED CRC-32 that java.util.zip verifies — the items-only JVM test
        // only exercises text. Reading via ZipInputStream throws ZipException on any CRC/size mismatch.
        val repo = newRepo()
        val bytes = ByteArray(512) { (it * 7 - 3).toByte() } // includes 0x80..0xFF
        repo.save("braindump:t1", "t1", "r.wav", "audio/wav", bytes, created)

        val zip = exporter(listOf(task("t1")), repo).buildBackupZip()

        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            generateSequence { zis.nextEntry }
                .first { it.name == "attachments/braindump:t1" }
            assertContentEquals(bytes, zis.readBytes()) // throws on CRC mismatch
        }
    }

    @Test
    fun import_restores_bytes_as_a_relinked_local_attachment_offline() = runTest {
        val source = newRepo()
        val audio = byteArrayOf(9, 8, 7, 6)
        source.save("braindump:t1", "t1", "d.wav", "audio/wav", audio, created, caption = "note")
        val zip = exporter(listOf(task("t1")), source).buildBackupZip()

        // A fresh install: a brand-new attachment repo (own DB + own byte store) — the "wipe" in the AC.
        val restored = newRepo()
        val f = ImportFixture(restored)

        assertEquals(ImportResult.Restored(1), f.importer.import(zip))

        // AC: bytes back and playable offline — read straight from the on-device store, no network.
        assertContentEquals(audio, restored.bytes("braindump:t1"))
        val row = restored.get("braindump:t1")!!
        assertEquals("t1", row.taskId) // re-linked to the restored item
        assertEquals("d.wav", row.filename)
        assertEquals("audio/wav", row.mime)
        assertEquals("note", row.caption)
        assertEquals(created, row.createdAt) // the recording's own timestamp round-trips, not the import clock
        assertEquals(StorageProviderId.OnDevice, row.provider) // local, never re-uploaded
        assertEquals(listOf("braindump:t1"), restored.forTask("t1").map { it.id })
    }

    @Test
    fun multiple_attachments_on_a_task_all_embed_and_all_restore() = runTest {
        val source = newRepo()
        val first = byteArrayOf(1, 1, 1)
        val second = byteArrayOf(2, 2, 2, 2)
        // Two distinct on-device attachments on the same Task — the export fan-out and the import loop each
        // process a list, so a first-only regression or a colliding zip path would surface here (not in the
        // single-attachment tests, which pin to `.single()`).
        source.save("att-a", "t1", "a.wav", "audio/wav", first, created)
        source.save("att-b", "t1", "b.wav", "audio/wav", second, created)
        val zip = exporter(listOf(task("t1")), source).buildBackupZip()

        val entries = unzipStored(zip)
        assertEquals(setOf("items.json", "attachments/att-a", "attachments/att-b"), entries.keys)
        assertContentEquals(first, entries["attachments/att-a"])
        assertContentEquals(second, entries["attachments/att-b"])
        assertEquals(setOf("att-a", "att-b"), decodeTasks(entries["items.json"]!!.decodeToString()).single().localAttachments.map { it.id }.toSet())

        val restored = newRepo()
        assertEquals(ImportResult.Restored(1), ImportFixture(restored).importer.import(zip))
        assertEquals(setOf("att-a", "att-b"), restored.forTask("t1").map { it.id }.toSet())
        assertContentEquals(first, restored.bytes("att-a"))
        assertContentEquals(second, restored.bytes("att-b"))
    }

    @Test
    fun reimporting_does_not_duplicate_the_restored_attachment() = runTest {
        val source = newRepo()
        source.save("braindump:t1", "t1", "d.wav", "audio/wav", byteArrayOf(1, 2), created)
        val zip = exporter(listOf(task("t1")), source).buildBackupZip()

        val restored = newRepo()
        val f = ImportFixture(restored)
        f.importer.import(zip)
        f.importer.import(zip)

        // save() is INSERT OR REPLACE keyed on the attachment id → replace, never duplicate.
        assertEquals(1, restored.forTask("t1").size)
    }

    @Test
    fun a_manifest_referencing_a_missing_attachment_blob_is_malformed_and_writes_nothing() = runTest {
        val ghost = ItemView.Task(
            id = "t1", orgSlug = "u-e4h2qk", title = "t1", dateCreated = created.toString(),
            localAttachments = listOf(LocalAttachmentDto("ghost", "x.wav", "audio/wav", 1L, null, created.toString())),
        )
        val manifest = DefernoJson.encodeToString(
            Envelope.serializer(ListSerializer(ItemView.serializer())),
            Envelope("0.1", listOf<ItemView>(ghost)),
        )
        val zip = zipStored(listOf("items.json" to manifest.encodeToByteArray())) // no attachments/ghost entry

        val restored = newRepo()
        val f = ImportFixture(restored)

        assertEquals(ImportResult.Malformed, f.importer.import(zip))
        // Nothing written — not the item, not the attachment (the pure pass caught the missing blob).
        assertTrue(f.taskStore.all.isEmpty())
        assertTrue(f.outbox.all.isEmpty())
        assertNull(restored.get("ghost"))
    }

    private fun decodeTasks(itemsJson: String): List<ItemView.Task> =
        DefernoJson.decodeFromString(Envelope.serializer(ListSerializer(ItemView.serializer())), itemsJson)
            .data.filterIsInstance<ItemView.Task>()
}
