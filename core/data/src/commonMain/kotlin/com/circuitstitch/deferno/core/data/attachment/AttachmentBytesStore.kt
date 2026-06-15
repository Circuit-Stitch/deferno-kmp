package com.circuitstitch.deferno.core.data.attachment

/**
 * The on-device attachment **byte store** (#210): persists an attachment's bytes in app-local storage and
 * reads/deletes them by a provider-relative [locator] (the attachment id). The platform actuals
 * ([FileAttachmentBytesStore] on Android/desktop) write under an app-private directory, so the bytes survive
 * offline and never leave the device. A seam (an interface, **not** `expect class`) so iOS — whose
 * NSFileManager actual is a follow-up — keeps compiling without a binding breaking the iOS klib.
 */
interface AttachmentBytesStore {
    /** Persist [bytes] under [locator], overwriting any existing bytes there. */
    suspend fun write(locator: String, bytes: ByteArray)

    /** The bytes stored under [locator], or `null` if none. */
    suspend fun read(locator: String): ByteArray?

    /** Remove the bytes stored under [locator] (a no-op if absent). */
    suspend fun delete(locator: String)
}

/**
 * A non-persistent [AttachmentBytesStore] for tests and the targets whose file actual is a follow-up
 * (iOS/macOS, #210). **Measured** (commonTest). Process-lifetime only — fine as an AppScope placeholder.
 */
class InMemoryAttachmentBytesStore : AttachmentBytesStore {
    private val bytes = mutableMapOf<String, ByteArray>()

    override suspend fun write(locator: String, bytes: ByteArray) {
        this.bytes[locator] = bytes
    }

    override suspend fun read(locator: String): ByteArray? = bytes[locator]

    override suspend fun delete(locator: String) {
        bytes.remove(locator)
    }
}
