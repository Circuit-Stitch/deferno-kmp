package com.circuitstitch.deferno.core.sidecar

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal

/**
 * The POSIX (macOS / Linux) [PeerTrust] (ADR-0009/0024): the socket path must be **owned by the current
 * user** and **not reachable by group or other** (effectively mode `0600`/`0700`). This is the strongest
 * peer assertion the JVM can make portably without native code — it asserts the peer's uid via the
 * filesystem and defends against another user planting a socket at the well-known path to intercept the
 * privacy-critical [[Transcript]] stream.
 *
 * (The kernel `getpeereid` check is the Helper's job, #121; this is the client's complementary half.)
 *
 * [expectedOwner] defaults to the current `user.name` and is injectable for testing the mismatch branch.
 */
class PosixPeerTrust(
    private val expectedOwner: String = System.getProperty("user.name").orEmpty(),
) : PeerTrust {

    override fun verify(address: Path) {
        val owner: UserPrincipal = Files.getOwner(address)
        val expected: UserPrincipal? = runCatching {
            address.fileSystem.userPrincipalLookupService.lookupPrincipalByName(expectedOwner)
        }.getOrNull()
        if (expected == null || owner != expected) {
            throw SidecarSecurityException(
                "Sidecar socket at $address is not owned by the current user ($expectedOwner)",
            )
        }

        val perms: Set<PosixFilePermission> = try {
            Files.getPosixFilePermissions(address)
        } catch (e: UnsupportedOperationException) {
            // A non-POSIX filesystem — the wrong PeerTrust for this OS (a Windows pipe needs an ACL check).
            throw SidecarSecurityException("cannot verify POSIX permissions of $address", e)
        }
        if (perms.any { it in NON_OWNER_PERMISSIONS }) {
            throw SidecarSecurityException(
                "Sidecar socket at $address is group/other-accessible (must be owner-only): $perms",
            )
        }
    }

    private companion object {
        /** Any of these set means the socket is reachable beyond its owner — rejected. */
        val NON_OWNER_PERMISSIONS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}
