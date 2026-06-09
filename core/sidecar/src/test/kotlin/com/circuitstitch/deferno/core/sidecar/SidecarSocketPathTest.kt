package com.circuitstitch.deferno.core.sidecar

import kotlin.test.Test
import kotlin.test.assertTrue

class SidecarSocketPathTest {

    @Test
    fun linuxPrefersTheXdgRuntimeDir() {
        val path = SidecarSocketPath.default(
            os = "linux",
            home = "/home/u",
            env = { if (it == "XDG_RUNTIME_DIR") "/run/user/1000" else null },
        ).toString()
        assertTrue(path.contains("/run/user/1000"), path)
        assertTrue(path.endsWith("deferno/sidecar.sock"), path)
    }

    @Test
    fun linuxFallsBackToTmpWithoutXdg() {
        val path = SidecarSocketPath.default(os = "linux", home = "/home/u", env = { null }).toString()
        assertTrue(path.startsWith("/tmp/"), path)
        assertTrue(path.endsWith("deferno/sidecar.sock"), path)
    }

    @Test
    fun macUsesApplicationSupport() {
        val path = SidecarSocketPath.default(os = "mac os x", home = "/Users/u", env = { null }).toString()
        assertTrue(path.contains("/Users/u/Library/Application Support/Deferno"), path)
        assertTrue(path.endsWith("sidecar.sock"), path)
    }

    @Test
    fun windowsUsesLocalAppData() {
        val path = SidecarSocketPath.default(
            os = "windows 11",
            home = "C:\\Users\\u",
            env = { if (it == "LOCALAPPDATA") "C:\\Users\\u\\AppData\\Local" else null },
        ).toString()
        assertTrue(path.contains("AppData") && path.contains("Local"), path)
        assertTrue(path.contains("Deferno") && path.contains("sidecar.sock"), path)
    }
}
