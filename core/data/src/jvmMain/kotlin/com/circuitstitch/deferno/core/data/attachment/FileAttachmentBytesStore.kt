package com.circuitstitch.deferno.core.data.attachment

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The on-device [AttachmentBytesStore] over the local filesystem (#210): attachment bytes live as files
 * under an app-private [baseDir] (desktop `<databasesDir>/attachments`), so they survive offline and never
 * leave the device. IO hops to [Dispatchers.IO]. Plain `java.io.File`, so it runs headless — its round-trip
 * is the validated test for the seam (the Android twin is an identical copy, the KMP per-actual pattern like
 * `WhisperSpeechToText`). Coverage-excluded with the Android twin (one glob covers both) since both are
 * platform file IO (ADR-0006).
 */
class FileAttachmentBytesStore(
    private val baseDir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentBytesStore {
    override suspend fun write(locator: String, bytes: ByteArray): Unit = withContext(dispatcher) {
        baseDir.mkdirs()
        File(baseDir, locator).writeBytes(bytes)
    }

    override suspend fun read(locator: String): ByteArray? = withContext(dispatcher) {
        File(baseDir, locator).let { if (it.exists()) it.readBytes() else null }
    }

    override suspend fun delete(locator: String) {
        withContext(dispatcher) { File(baseDir, locator).delete() }
    }
}
