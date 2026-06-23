package com.circuitstitch.deferno.core.data.attachment

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * The on-device [AttachmentBytesStore] over the Apple filesystem (#210/#267): attachment bytes live as files
 * under an app-private [directoryPath] (Application Support `deferno/attachments`), so a Brain dump's retained
 * recording survives relaunch and never leaves the device — the iOS/macOS twin of [FileAttachmentBytesStore]
 * (which is `java.io.File`, Android/desktop only). IO hops off the main thread. Platform file IO, exercised on
 * a real device — coverage-excluded like the DB drivers (ADR-0006); the round-trip is validated by the
 * desktop twin's jvmTest and an iosTest.
 *
 * Replaces the iOS [InMemoryAttachmentBytesStore] placeholder, whose bytes were lost on next launch — so a
 * Salvage draft's recording now actually attaches when accepted in the Inbox later (ADR-0037).
 */
@OptIn(ExperimentalForeignApi::class)
class AppleFileAttachmentBytesStore(
    private val directoryPath: String = defaultDirectoryPath(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AttachmentBytesStore {

    override suspend fun write(locator: String, bytes: ByteArray) {
        withContext(dispatcher) {
            NSFileManager.defaultManager.createDirectoryAtPath(
                directoryPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            bytes.toNSData().writeToFile(pathFor(locator), atomically = true)
        }
    }

    override suspend fun read(locator: String): ByteArray? = withContext(dispatcher) {
        NSData.dataWithContentsOfFile(pathFor(locator))?.toByteArray()
    }

    override suspend fun delete(locator: String) {
        withContext(dispatcher) {
            NSFileManager.defaultManager.removeItemAtPath(pathFor(locator), error = null)
        }
    }

    private fun pathFor(locator: String): String = "$directoryPath/$locator"

    private companion object {
        fun defaultDirectoryPath(): String {
            val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: NSTemporaryDirectory()
            return "$base/deferno/attachments"
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), this@toByteArray.bytes, length) }
    }
}
