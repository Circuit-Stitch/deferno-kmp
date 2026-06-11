package com.circuitstitch.deferno.core.sidecar

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * Resolves the in-band auth token the client sends in its [SidecarFrame.Hello] (peer-auth leg 2,
 * ADR-0009/0024) — the JVM mirror of the Helper's `SidecarToken.swift`, so both peers resolve the same
 * out-of-band secret the same way:
 *
 * 1. the `DEFERNO_SIDECAR_TOKEN` environment variable (dev runs / a launcher that injects it), else
 * 2. `DEFERNO_SIDECAR_TOKEN_FILE` — a `0600`, owner-only file both peers read (the packaged
 *    LaunchAgent provisioning, #122).
 *
 * Resolution **degrades, never throws**: a missing/empty/unreadable/insecure source yields `null`, the
 * caller hands the client an empty token, the Helper rejects the handshake (`unauthenticated`), and the
 * consumer falls back exactly as if no Helper were bound (e.g. the selector keeps the whisper floor,
 * ADR-0018). An insecure token file (group/other-accessible, or not the current user's) is *refused*,
 * not trusted — the same posture as [PosixPeerTrust] on the socket path and the Helper on its side.
 *
 * The token is a secret: it is never logged (ADR-0009).
 */
object SidecarTokenSource {

    /** The direct-value environment variable — same name the Helper reads (`SidecarToken.swift`). */
    const val ENV_TOKEN: String = "DEFERNO_SIDECAR_TOKEN"

    /** The token-file environment variable — same name the Helper reads (`SidecarToken.swift`). */
    const val ENV_TOKEN_FILE: String = "DEFERNO_SIDECAR_TOKEN_FILE"

    /**
     * The shared token, or `null` when none is provisioned (no Helper installed — the normal state on
     * Linux/Windows and on a Mac before #122 lands the packaged LaunchAgent).
     */
    fun resolve(
        env: (String) -> String? = System::getenv,
        currentUser: String = System.getProperty("user.name").orEmpty(),
    ): String? {
        env(ENV_TOKEN)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val filePath = env(ENV_TOKEN_FILE)?.takeIf { it.isNotBlank() } ?: return null
        return readOwnerOnlyFile(Paths.get(filePath), currentUser)
    }

    private fun readOwnerOnlyFile(path: Path, currentUser: String): String? {
        if (!fileIsOwnerOnly(path, currentUser)) return null
        val token = runCatching { Files.readString(path) }.getOrNull()?.trim()
        return token?.takeIf { it.isNotEmpty() }
    }

    /** True iff [path] is owned by [currentUser] with no group/other permission bits (mirrors the Helper). */
    private fun fileIsOwnerOnly(path: Path, currentUser: String): Boolean {
        val owner = runCatching { Files.getOwner(path) }.getOrNull() ?: return false
        val expected = runCatching {
            path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(currentUser)
        }.getOrNull() ?: return false
        if (owner != expected) return false
        val perms = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return false
        return perms.none { it in NON_OWNER_PERMISSIONS }
    }

    /** Any of these set means the token file is readable/plantable beyond its owner — refused. */
    private val NON_OWNER_PERMISSIONS: Set<PosixFilePermission> = setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE,
    )
}
