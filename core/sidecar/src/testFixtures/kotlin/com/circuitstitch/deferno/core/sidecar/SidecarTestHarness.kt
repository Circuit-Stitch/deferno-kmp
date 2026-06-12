package com.circuitstitch.deferno.core.sidecar

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The shared rig for Linux fast-path E2E suites (#118/#119, ADR-0024): a [StubHelper] bound to a short
 * tmp socket path plus a real [SidecarClient] dialing it, torn down in one [close]. It lives in
 * testFixtures next to [StubHelper] so every consumer module's E2E test — this module's client suite,
 * core/speech's engine + selector suite, #120's permission UX next — drives the identical rig instead
 * of re-deriving the socket-path, lifecycle, and deadline subtleties.
 *
 * Use as a test field with an `@AfterTest` calling [close]:
 * ```
 * private val harness = SidecarTestHarness()
 * @AfterTest fun tearDown() = harness.close()
 * ```
 */
class SidecarTestHarness : AutoCloseable {

    private val cleanups = mutableListOf<() -> Unit>()

    /**
     * Start a [StubHelper]; closed (socket unbound, path deleted) by [close]. Pass an explicit [path]
     * to re-bind the same path across stubs (the client's re-dial scenarios).
     */
    fun startStub(
        token: String = TOKEN,
        capabilities: Set<String> = StubHelper.DEFAULT_CAPABILITIES,
        path: Path = socketDir().resolve("h.sock"),
    ): StubHelper {
        val stub = StubHelper(path, expectedToken = token, capabilities = capabilities)
        stub.start()
        cleanups += { stub.close() }
        return stub
    }

    /** A real production client ([unixSocketSidecarClient]) over [path]; closed by [close]. */
    fun client(path: Path, token: String = TOKEN): SidecarClient =
        unixSocketSidecarClient(path, token).also { client -> cleanups += { client.close() } }

    /**
     * A fresh directory for socket paths, preferring `/tmp` over `java.io.tmpdir`: AF_UNIX caps the
     * whole path around ~104 bytes, and a deep per-user tmpdir can silently exceed it.
     */
    fun socketDir(): Path {
        val tmp = Paths.get("/tmp")
        val base = if (Files.isDirectory(tmp) && Files.isWritable(tmp)) tmp else Paths.get(System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(base, "deferno-sidecar")
        cleanups += { runCatching { dir.toFile().deleteRecursively() } }
        return dir
    }

    /** Tear everything down in reverse creation order; failures are swallowed (best-effort cleanup). */
    override fun close() {
        cleanups.asReversed().forEach { runCatching { it() } }
        cleanups.clear()
    }

    companion object {
        /** The canned in-band token both halves of the rig share. */
        const val TOKEN: String = "shared-in-band-token"

        /**
         * Last-resort failure bound for awaited round-trips, **not** an expectation: they complete in
         * milliseconds, but a loaded runner (full `check`, 2-core CI) can starve a test worker for whole
         * seconds — Turbine's default 3s `awaitItem` and a 2s `withTimeout` both flaked under exactly
         * that (#161). Pass it to every `.test(timeout = …)` and `withTimeout(…)` that crosses the real
         * socket; it only costs time when failing.
         */
        val DEADLINE: Duration = 30.seconds

        /** Whether this filesystem supports POSIX views — AF_UNIX + 0600-permission tests gate on it. */
        fun posixSupported(): Boolean =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    }
}
