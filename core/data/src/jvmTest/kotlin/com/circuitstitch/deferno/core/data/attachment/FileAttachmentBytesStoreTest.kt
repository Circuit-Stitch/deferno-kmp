package com.circuitstitch.deferno.core.data.attachment

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real-filesystem round-trip for [FileAttachmentBytesStore] (#210, ADR-0006 JVM-fast path): proves bytes
 * write to / read from / delete from an app-private dir entirely on-device (no network). The Android twin is
 * an identical `java.io.File` copy, so this validates that seam too (it is coverage-excluded as platform IO).
 */
class FileAttachmentBytesStoreTest {

    private val baseDir = Files.createTempDirectory("deferno-attach").toFile()
    private val store = FileAttachmentBytesStore(baseDir)

    @AfterTest
    fun cleanup() {
        baseDir.deleteRecursively()
    }

    @Test
    fun writeReadDeleteRoundTrip() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        store.write("att-1", bytes)

        assertContentEquals(bytes, store.read("att-1"))

        store.delete("att-1")
        assertNull(store.read("att-1"))
    }

    @Test
    fun readMissingLocatorIsNull() = runTest {
        assertNull(store.read("nope"))
    }

    @Test
    fun writeCreatesTheBaseDirIfAbsent() = runTest {
        val nested = FileAttachmentBytesStore(baseDir.resolve("sub").resolve("dir"))
        nested.write("a", byteArrayOf(9))

        assertContentEquals(byteArrayOf(9), nested.read("a"))
        assertTrue(baseDir.resolve("sub").resolve("dir").resolve("a").exists())
    }
}
