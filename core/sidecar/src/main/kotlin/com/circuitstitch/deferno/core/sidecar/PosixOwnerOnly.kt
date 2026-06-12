package com.circuitstitch.deferno.core.sidecar

import java.nio.file.attribute.PosixFilePermission

/**
 * Any of these bits set means a path is reachable beyond its owner. The one definition behind the
 * client's two POSIX owner-only trust checks — [PosixPeerTrust] on the socket path (refuses with
 * [SidecarSecurityException]) and [SidecarTokenSource] on the token file (degrades to `null`) — so the
 * two postures can't drift (ADR-0009).
 */
internal val POSIX_NON_OWNER_PERMISSIONS: Set<PosixFilePermission> = setOf(
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_WRITE,
    PosixFilePermission.OTHERS_EXECUTE,
)

/** True iff no group/other bit is set (effectively mode `0600`/`0700`). */
internal fun Set<PosixFilePermission>.isOwnerOnly(): Boolean = none { it in POSIX_NON_OWNER_PERMISSIONS }
