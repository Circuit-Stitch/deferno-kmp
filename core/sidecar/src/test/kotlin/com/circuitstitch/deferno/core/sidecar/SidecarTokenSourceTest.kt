package com.circuitstitch.deferno.core.sidecar

import com.circuitstitch.deferno.core.sidecar.SidecarTestHarness.Companion.posixSupported
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The client-half token resolution (#119, peer-auth leg 2): env wins, then an owner-only token file —
 * and every degraded source (absent, empty, insecure, unreadable) resolves to `null` rather than
 * throwing, so the consumer falls back to the whisper floor exactly as if no Helper were bound.
 */
class SidecarTokenSourceTest {

    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanups.asReversed().forEach { runCatching { it() } }
    }

    @Test
    fun resolvesTheDirectEnvironmentVariableFirst() {
        val token = SidecarTokenSource.resolve(env = mapOf(SidecarTokenSource.ENV_TOKEN to " s3cret \n")::get)
        assertEquals("s3cret", token)
    }

    @Test
    fun envValueWinsOverTheTokenFile() {
        if (!posixSupported()) return
        val file = tokenFile("from-file")
        val token = SidecarTokenSource.resolve(
            env = mapOf(
                SidecarTokenSource.ENV_TOKEN to "from-env",
                SidecarTokenSource.ENV_TOKEN_FILE to file.toString(),
            )::get,
        )
        assertEquals("from-env", token)
    }

    @Test
    fun readsAnOwnerOnlyTokenFile() {
        if (!posixSupported()) return
        val file = tokenFile("file-token\n")
        val token = SidecarTokenSource.resolve(
            env = mapOf(SidecarTokenSource.ENV_TOKEN_FILE to file.toString())::get,
        )
        assertEquals("file-token", token)
    }

    @Test
    fun refusesAGroupOrOtherAccessibleTokenFile() {
        if (!posixSupported()) return
        val file = tokenFile("leaked", perms = "rw-r-----")
        val token = SidecarTokenSource.resolve(
            env = mapOf(SidecarTokenSource.ENV_TOKEN_FILE to file.toString())::get,
        )
        assertNull(token, "an insecure token file must be refused, not trusted")
    }

    @Test
    fun refusesAFileOwnedByAnotherUser() {
        if (!posixSupported()) return
        val file = tokenFile("not-mine")
        val token = SidecarTokenSource.resolve(
            env = mapOf(SidecarTokenSource.ENV_TOKEN_FILE to file.toString())::get,
            currentUser = "definitely-not-this-user",
        )
        assertNull(token)
    }

    @Test
    fun resolvesNullWhenNothingIsProvisioned() {
        assertNull(SidecarTokenSource.resolve(env = { null }))
    }

    @Test
    fun resolvesNullForABlankEnvValueAndNoFile() {
        assertNull(SidecarTokenSource.resolve(env = mapOf(SidecarTokenSource.ENV_TOKEN to "  ")::get))
    }

    @Test
    fun resolvesNullForAMissingOrEmptyTokenFile() {
        if (!posixSupported()) return
        val missing = SidecarTokenSource.resolve(
            env = mapOf(SidecarTokenSource.ENV_TOKEN_FILE to "/nonexistent/deferno.token")::get,
        )
        assertNull(missing)

        val empty = tokenFile("   \n")
        assertNull(
            SidecarTokenSource.resolve(env = mapOf(SidecarTokenSource.ENV_TOKEN_FILE to empty.toString())::get),
        )
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun tokenFile(contents: String, perms: String = "rw-------"): Path {
        val dir = Files.createTempDirectory("deferno-token")
        cleanups += { runCatching { dir.toFile().deleteRecursively() } }
        val file = dir.resolve("sidecar.token")
        Files.writeString(file, contents)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(perms))
        return file
    }
}
