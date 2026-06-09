package com.circuitstitch.deferno.core.sidecar

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PosixPeerTrustTest {

    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() = cleanups.forEach { runCatching { it() } }

    @Test
    fun acceptsAnOwnerOnlyPathOwnedByTheCurrentUser() {
        if (!posix()) return
        val path = tempFile("rw-------")
        PosixPeerTrust().verify(path) // no throw
    }

    @Test
    fun acceptsAnOwnerReadWriteExecutePath() {
        if (!posix()) return
        // A bind may leave the owner-execute bit set; only group/other access is forbidden.
        val path = tempFile("rwx------")
        PosixPeerTrust().verify(path) // no throw
    }

    @Test
    fun rejectsAGroupOrOtherAccessiblePath() {
        if (!posix()) return
        val path = tempFile("rw-r--r--")
        assertFailsWith<SidecarSecurityException> { PosixPeerTrust().verify(path) }
    }

    @Test
    fun rejectsAPathNotOwnedByTheExpectedUser() {
        if (!posix()) return
        val path = tempFile("rw-------")
        val trust = PosixPeerTrust(expectedOwner = "no-such-user-deferno-zzz")
        assertFailsWith<SidecarSecurityException> { trust.verify(path) }
    }

    private fun tempFile(perms: String): Path {
        val path = Files.createTempFile("peer-trust", ".sock")
        cleanups += { Files.deleteIfExists(path) }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms))
        return path
    }

    private fun posix(): Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
}
