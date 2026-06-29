package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.task.FakeTaskLocalStore
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The hand-rolled, dependency-free [zipStored] writer must produce a genuinely valid zip: this opens
 * the Backup file with the JVM's own `java.util.zip.ZipInputStream` (which verifies the STORED entry's
 * CRC-32 + sizes) and asserts the single `items.json` entry round-trips byte-for-byte. The JVM is
 * the natural place to prove the format — `java.util.zip` is the independent reader the K/N targets lack.
 */
class BackupZipJvmTest {

    @Test
    fun zip_is_readable_by_java_util_zip_and_items_json_matches() = runTest {
        val exporter = BackupExporter(
            taskStore = FakeTaskLocalStore(
                mapOf(
                    TaskId("t1") to Task(
                        id = TaskId("t1"),
                        orgSlug = "u-e4h2qk",
                        title = "Buy milk",
                        workingState = WorkingState.Open,
                        dateCreated = Instant.parse("2026-05-20T16:11:42Z"),
                        hydration = HydrationState.Full,
                        description = "skim",
                    ),
                ),
            ),
            habitStore = FakeHabitLocalStore(),
            choreStore = FakeChoreLocalStore(),
            eventStore = FakeEventLocalStore(),
        )

        val expectedItemsJson = exporter.buildItemsJson()
        val zip = exporter.buildBackupZip()

        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            val entry = zis.nextEntry
            assertNotNull(entry, "zip has no entries")
            assertEquals("items.json", entry.name)
            val content = zis.readBytes().decodeToString() // throws ZipException on CRC/size mismatch
            assertEquals(expectedItemsJson, content)
            assertNull(zis.nextEntry, "zip has unexpected extra entries")
        }
    }
}
