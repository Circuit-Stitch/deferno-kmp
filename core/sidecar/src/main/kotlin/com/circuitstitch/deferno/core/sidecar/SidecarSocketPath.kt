package com.circuitstitch.deferno.core.sidecar

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The well-known socket path the Sidecar client dials by default (ADR-0024). The client accepts **any**
 * configured path (the #118 acceptance criterion, and what lets the Linux stub bind a temp path in
 * tests) — this just supplies the per-OS default the desktop app uses in production, mirroring the OS
 * branching of `app/desktopApp`'s `defernoDatabasesDir()`:
 *
 * - **Linux:** `$XDG_RUNTIME_DIR/deferno/sidecar.sock` (a per-user, tmpfs runtime dir), else `/tmp`.
 * - **macOS:** `~/Library/Application Support/Deferno/sidecar.sock` — the path launchd also binds (#121).
 * - **Windows:** under `%LOCALAPPDATA%` for now (AF_UNIX); a `NamedPipeTransport` will use a pipe name
 *   instead when the Windows Helper lands (ADR-0025).
 *
 * **Caveat:** Unix socket paths are length-limited (~108 bytes Linux, ~104 macOS); these defaults stay
 * well under for ordinary home/runtime dirs.
 */
object SidecarSocketPath {

    private const val SOCKET_FILE = "sidecar.sock"

    fun default(
        os: String = System.getProperty("os.name").orEmpty().lowercase(),
        home: String = System.getProperty("user.home").orEmpty(),
        env: (String) -> String? = System::getenv,
    ): Path = when {
        "win" in os -> {
            val base = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Local"
            Paths.get(base, "Deferno", SOCKET_FILE)
        }

        "mac" in os || "darwin" in os ->
            Paths.get(home, "Library", "Application Support", "Deferno", SOCKET_FILE)

        else -> {
            val runtime = env("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: "/tmp"
            Paths.get(runtime, "deferno", SOCKET_FILE)
        }
    }
}
