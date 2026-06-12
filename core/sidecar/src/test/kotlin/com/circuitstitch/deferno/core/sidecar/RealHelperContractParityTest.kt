package com.circuitstitch.deferno.core.sidecar

import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * **Contract parity (#121): the real [DefaultSidecarClient] over a real AF_UNIX socket talking to the
 * real, Developer-ID-signed Swift Helper** (`helpers/macos`) running in its `--contract-fixtures` mode —
 * the byte-for-byte counterpart of [SidecarClientE2ETest] (which drives the in-JVM [StubHelper]). It is
 * the executable proof that **swapping the Linux stub for the real Helper requires no JVM-side changes**:
 * the same client code, the same assertions, a different (native, in-another-language) server.
 *
 * `--contract-fixtures` makes the Swift Helper emit the *same canned* handshake / permission / transcript
 * frames the stub does, so this proves the Helper's real codec, peer-auth, streaming, push, and cancel
 * with **no TCC / mic dependency**. The real SFSpeech + TCC paths are human-gated (a person must grant
 * the prompts and speak) and are documented in `helpers/macos/README.md`, not asserted here.
 *
 * **Mac-gated, like every Mac-only task in this repo** (ADR-0006): off macOS, or when the signed Helper
 * binary hasn't been built, every test no-ops (returns) — so Linux/Windows CI is untouched. Build the
 * binary first with `helpers/macos/scripts/build.sh` (or point [HELPER_ENV] at it).
 */
class RealHelperContractParityTest {

    private val token = "shared-in-band-token"
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanups.asReversed().forEach { runCatching { it() } }
    }

    @Test
    fun connectsAuthenticatesAndSurfacesCapabilities() = withHelper { client ->
        client.connect()
        assertEquals(
            setOf(
                SidecarCapabilities.Permissions,
                SidecarCapabilities.SpeechTranscribe,
                SidecarCapabilities.Notifications,
                SidecarCapabilities.StatusItem,
                SidecarCapabilities.Hotkeys,
            ),
            client.capabilities(),
        )
        assertIs<SidecarConnectionState.Ready>(client.state.value)
    }

    @Test
    fun completesARequestResponseRoundTrip() = withHelper { client ->
        client.connect()
        val result = checkNotNull(client.request(SidecarMethods.QueryPermission))
        val status = SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), result)
        assertEquals("speech", status.capability)
        assertEquals(PermissionStatusValue.GRANTED, status.status)
    }

    @Test
    fun receivesAnUnsolicitedPush() = withHelper { client ->
        client.connect()
        client.pushes.test {
            client.request(SidecarMethods.QueryPermission)
            val push = awaitItem()
            assertEquals(SidecarTopics.PermissionChanged, push.topic)
            val status = SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), push.payload)
            assertEquals(PermissionStatusValue.GRANTED, status.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun consumesAServerStream() = withHelper { client ->
        client.connect()
        client.openStream(SidecarMethods.SubscribeTranscript).test {
            assertEquals(TranscriptWire.Partial("hel"), transcript(awaitItem()))
            assertEquals(TranscriptWire.Partial("hello wor"), transcript(awaitItem()))
            assertEquals(TranscriptWire.Final("hello world"), transcript(awaitItem()))
            awaitComplete()
        }
    }

    @Test
    fun cancellingTheStreamLeavesTheHelperAlive() = withHelper { client ->
        client.connect()
        client.openStream(SidecarMethods.SubscribeTranscript).test {
            awaitItem() // first partial, then abandon the stream → the client sends Cancel
            // The contract permits in-flight events to race ahead of the Cancel — and unlike the in-JVM
            // stub, this cancel crosses a real process boundary, so the canned helper's 50ms event gap
            // is regularly outrun. Tolerate leftovers; the real assertion is the round-trip below.
            cancelAndIgnoreRemainingEvents()
        }
        // The Helper must have handled the Cancel (released the stream) and stayed up: a follow-up
        // request on the same connection still round-trips.
        val result = checkNotNull(client.request(SidecarMethods.QueryPermission))
        assertEquals("speech", SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), result).capability)
    }

    @Test
    fun postsANotificationThroughThePort() = withHelper { client ->
        client.connect()
        val port = SidecarNotificationPort(client)
        assertEquals(true, port.isAvailable())
        // The canned provider acks a granted post with an empty response — no exception is the proof.
        port.post(PostNotificationWire(title = "Deferno", body = "contract parity"))
    }

    @Test
    fun echoesTheQueriedPermissionCapability() = withHelper { client ->
        client.connect()
        assertEquals(PermissionStatusValue.GRANTED, SidecarNotificationPort(client).permission())
    }

    @Test
    fun resolvesAPermissionThroughTheRealRequestFlow() = withHelper(
        // The #120 canned TCC prompt: the helper settles a not_determined request to the canned outcome.
        fixturePermission = "not_determined",
        fixtureRequestOutcome = "granted",
    ) { client ->
        val port = DefaultSidecarPermissionPort(client)
        assertEquals(PermissionStatusValue.NOT_DETERMINED, port.status(SidecarPermissionCapabilities.Microphone))
        assertEquals(PermissionStatusValue.GRANTED, port.request(SidecarPermissionCapabilities.Microphone))
        // Settled: introspection now reports the grant (and still never prompts).
        assertEquals(PermissionStatusValue.GRANTED, port.status(SidecarPermissionCapabilities.Microphone))
    }

    @Test
    fun showsTheStatusItemAndReceivesItsClickPush() = withHelper { client ->
        client.connect()
        val port = SidecarStatusItemPort(client)
        assertEquals(true, port.isAvailable())
        port.clicks.test {
            port.setVisible(true) // canned: ack, then one simulated click push
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun registersAHotkeyAndReceivesItsFirePush() = withHelper { client ->
        client.connect()
        val port = SidecarHotkeyPort(client)
        assertEquals(true, port.isAvailable())
        port.fires.test {
            port.register(7, "d", setOf(HotkeyModifier.COMMAND, HotkeyModifier.SHIFT))
            assertEquals(7L, awaitItem()) // canned: ack, then one simulated fire push
            cancelAndIgnoreRemainingEvents()
        }
        port.unregister(7) // idempotent ack round-trips
    }

    @Test
    fun rejectsAnInvalidToken() = withHelper(clientToken = "wrong-token") { client ->
        assertFailsWith<SidecarSecurityException> { client.connect() }
        assertIs<SidecarConnectionState.Disconnected>(client.state.value)
    }

    // --- harness -----------------------------------------------------------------------------------

    /**
     * Spawn the real Helper bound to a fresh temp socket in `--contract-fixtures` mode, hand the block a
     * real client, and tear both down. No-ops (skips) when the signed binary isn't available.
     */
    private fun withHelper(
        clientToken: String = token,
        fixturePermission: String = "granted",
        fixtureRequestOutcome: String = "granted",
        block: suspend (SidecarClient) -> Unit,
    ) {
        val binary = locateHelper() ?: return // skip: not on macOS / binary not built
        runBlocking {
            val socket = socketDir().resolve("h.sock")

            val process = ProcessBuilder(
                binary.absolutePath,
                "--listen", socket.toString(),
                "--token", token,
                "--contract-fixtures",
                "--fixture-permission", fixturePermission,
                "--fixture-request-outcome", fixtureRequestOutcome,
            ).redirectErrorStream(true).start()
            cleanups += { runCatching { process.destroyForcibly() } }

            awaitSocket(socket, process)

            val client = unixSocketSidecarClient(socket, clientToken)
            cleanups += { runCatching { client.close() } }
            block(client)
        }
    }

    /**
     * A short-pathed temp dir for the socket. AF_UNIX paths are length-limited (~104 bytes on macOS), so
     * — like [SidecarClientE2ETest] — prefer `/tmp` over `java.io.tmpdir` (`/var/folders/...` on macOS,
     * which is long enough to blow the limit).
     */
    private fun socketDir(): Path {
        val tmp = Paths.get("/tmp")
        val base = if (Files.isDirectory(tmp) && Files.isWritable(tmp)) tmp else Paths.get(System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(base, "deferno-sc")
        cleanups += { runCatching { dir.toFile().deleteRecursively() } }
        return dir
    }

    private fun awaitSocket(socket: Path, process: Process) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(socket)) return
            check(process.isAlive) { "helper exited before binding the socket" }
            Thread.sleep(25)
        }
        error("helper did not bind $socket within 5s")
    }

    private fun transcript(event: kotlinx.serialization.json.JsonElement): TranscriptWire =
        SidecarJson.decodeFromJsonElement(TranscriptWire.serializer(), event)

    /** Find the signed Helper binary: an explicit env override, else the default `dist/` build output. */
    private fun locateHelper(): File? {
        if (!System.getProperty("os.name").orEmpty().lowercase().let { "mac" in it || "darwin" in it }) {
            return null
        }
        System.getenv(HELPER_ENV)?.let { return File(it).takeIf { f -> f.canExecute() } }
        val root = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.exists(it.resolve("settings.gradle.kts")) }
            ?: Paths.get("").toAbsolutePath()
        return root.resolve("helpers/macos/dist/deferno-sidecar").toFile().takeIf { it.canExecute() }
    }

    private companion object {
        const val HELPER_ENV = "DEFERNO_SIDECAR_BINARY"
    }
}
