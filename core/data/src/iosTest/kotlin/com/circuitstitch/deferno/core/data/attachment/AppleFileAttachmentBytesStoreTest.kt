package com.circuitstitch.deferno.core.data.attachment

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * The [AppleFileAttachmentBytesStore] file IO against a real (temporary) directory: the byte-store contract
 * plus the property the in-memory placeholder could never give — **a fresh instance reads what a previous one
 * wrote**, so a Brain dump's Salvage recording survives relaunch and attaches on accept (ADR-0037). Also pins
 * the net-new `NSData`↔`ByteArray` round-trip (a subtle K/N pointer/length boundary).
 */
class AppleFileAttachmentBytesStoreTest {

    private val dir = "${NSTemporaryDirectory()}attachments-test-${Random.nextLong()}"
    private val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 1, 2, 3, 127, -128, -1)

    @Test
    fun roundTripsBytesByLocator() = runTest {
        val store = AppleFileAttachmentBytesStore(dir)
        store.write("braindump-audio-42", wav)
        assertContentEquals(wav, store.read("braindump-audio-42"))
    }

    @Test
    fun readsNullForAnAbsentLocator() = runTest {
        assertNull(AppleFileAttachmentBytesStore(dir).read("nope"))
    }

    @Test
    fun deleteRemovesTheBytes() = runTest {
        val store = AppleFileAttachmentBytesStore(dir)
        store.write("a", wav)
        store.delete("a")
        assertNull(store.read("a"))
    }

    @Test
    fun freshInstanceReadsWhatAPreviousOneWrote() = runTest {
        AppleFileAttachmentBytesStore(dir).write("keep", wav)
        // A new instance over the same directory = the next app launch (the relaunch-survives property).
        assertContentEquals(wav, AppleFileAttachmentBytesStore(dir).read("keep"))
    }

    @Test
    fun writeOverwritesExistingBytes() = runTest {
        val store = AppleFileAttachmentBytesStore(dir)
        store.write("x", wav)
        val replacement = byteArrayOf(9, 9, 9)
        store.write("x", replacement)
        assertContentEquals(replacement, store.read("x"))
    }
}
