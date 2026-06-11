package com.circuitstitch.deferno.desktop.chrome

import java.awt.image.BufferedImage
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * The graceful-degradation half of #117, asserted against the REAL AWT backend on the host this
 * suite actually runs on (Linux CI / any non-Mac dev box): no native app menu and no dock badge →
 * every install reports `false`, the badge is a silent no-op, and nothing throws. The macOS-true
 * half needs the macOS runtime — that's the issue's on-Mac verification, not a unit test.
 */
class AwtChromeBackendTest {

    @Test
    fun nonMacHost_reportsUnsupported_andNeverThrows() {
        assumeFalse(isMac())

        assertFalse(AwtChromeBackend.installAboutHandler {})
        assertFalse(AwtChromeBackend.installPreferencesHandler {})
        assertFalse(AwtChromeBackend.installQuitHandler {})
        assertFalse(AwtChromeBackend.badgeSupported)
        AwtChromeBackend.setBadge("3") // a silent no-op, never a throw
        AwtChromeBackend.setBadge(null)
        // No ICON_IMAGE dock off macOS either — same silent no-op contract.
        AwtChromeBackend.setDockIcon(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
    }

    private fun isMac(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().let { "mac" in it || "darwin" in it }
}
