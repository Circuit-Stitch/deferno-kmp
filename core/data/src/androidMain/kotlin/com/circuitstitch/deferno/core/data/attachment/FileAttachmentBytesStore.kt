package com.circuitstitch.deferno.core.data.attachment

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The on-device [AttachmentBytesStore] over the local filesystem (#210): attachment bytes live as files
 * under an app-private [baseDir] (Android `filesDir/attachments`), so they survive offline and never leave
 * the device. IO hops to [Dispatchers.IO]. Platform file IO, exercised on a real device — coverage-excluded
 * like the DB drivers (ADR-0006); the behaviour is validated by the desktop twin's jvmTest round-trip.
 * Duplicated across androidMain + jvmMain (the KMP per-actual pattern, like `WhisperSpeechToText`) since
 * both are plain `java.io.File`.
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
