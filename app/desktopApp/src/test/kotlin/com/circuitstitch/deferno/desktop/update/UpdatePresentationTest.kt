package com.circuitstitch.deferno.desktop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure [presentUpdate] mapping (#103): each [UpdateState] yields the right Help-menu wording,
 * primary action, enablement, and badge — verified without rendering (cf. `DesktopNavKindTest`). The
 * badge appears only when an update is available/applying, and only `Available` offers an INSTALL.
 */
class UpdatePresentationTest {

    @Test
    fun idle_offersCheck_noBadge() {
        val p = presentUpdate(UpdateState.Idle("0.1.0"))
        assertEquals("Deferno 0.1.0", p.versionLine)
        assertEquals(UpdateAction.CHECK, p.action)
        assertTrue(p.actionEnabled)
        assertNull(p.badge)
        assertNull(p.statusLine)
    }

    @Test
    fun checking_isDisabledInertNoBadge() {
        val p = presentUpdate(UpdateState.Checking("0.1.0"))
        assertEquals(UpdateAction.NONE, p.action)
        assertFalse(p.actionEnabled)
        assertNull(p.badge)
    }

    @Test
    fun upToDate_offersRecheck_withStatus() {
        val p = presentUpdate(UpdateState.UpToDate("0.1.0"))
        assertEquals(UpdateAction.CHECK, p.action)
        assertTrue(p.actionEnabled)
        assertEquals("You're up to date", p.statusLine)
        assertNull(p.badge)
    }

    @Test
    fun available_offersInstall_andShowsBadge() {
        val p = presentUpdate(UpdateState.Available("0.1.0", "0.2.0"))
        assertEquals(UpdateAction.INSTALL, p.action)
        assertTrue(p.actionEnabled)
        assertTrue(p.actionLabel.contains("0.2.0"))
        assertEquals("Update available", p.badge)
    }

    @Test
    fun installing_isInert_butStillBadged() {
        val p = presentUpdate(UpdateState.Installing("0.1.0", "0.2.0"))
        assertEquals(UpdateAction.NONE, p.action)
        assertFalse(p.actionEnabled)
        assertEquals("Updating…", p.badge)
    }

    @Test
    fun failed_offersRetry_withErrorStatus_noBadge() {
        val p = presentUpdate(UpdateState.Failed("0.1.0", "timeout"))
        assertEquals(UpdateAction.CHECK, p.action)
        assertTrue(p.actionEnabled)
        assertTrue(p.statusLine!!.contains("timeout"))
        assertNull(p.badge)
    }

    @Test
    fun unsupported_linux_pointsToPackageManager() {
        val p = presentUpdate(UpdateState.Unsupported("0.1.0", UnsupportedReason.PACKAGE_MANAGER))
        assertEquals(UpdateAction.VIEW_RELEASES, p.action)
        assertEquals("Updates are managed by your package manager", p.statusLine)
        assertNull(p.badge)
    }

    @Test
    fun unsupported_dev_pointsToInstalledApp() {
        val p = presentUpdate(UpdateState.Unsupported("0.1.0", UnsupportedReason.NOT_PACKAGED))
        assertEquals(UpdateAction.VIEW_RELEASES, p.action)
        assertEquals("Self-update is available in the installed app", p.statusLine)
    }
}
