package com.circuitstitch.deferno.desktop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure [presentUpdate] mapping (#103, #325): each [UpdateState] yields the right typed Help-menu
 * line keys, primary action, enablement, version arg, and badge — verified without rendering (cf.
 * `DesktopNavKindTest`). The View resolves each [UpdateStringKey] to a localized `update_*` string; the
 * badge appears only when an update is available/applying, and only `Available` offers an INSTALL.
 */
class UpdatePresentationTest {

    @Test
    fun idle_offersCheck_noBadge() {
        val p = presentUpdate(UpdateState.Idle("0.1.0"))
        assertEquals("0.1.0", p.currentVersion)
        assertEquals(UpdateStringKey.CHECK, p.actionLabel)
        assertEquals(UpdateAction.CHECK, p.action)
        assertTrue(p.actionEnabled)
        assertNull(p.badge)
        assertNull(p.statusLine)
        assertNull(p.versionArg)
    }

    @Test
    fun checking_isDisabledInertNoBadge() {
        val p = presentUpdate(UpdateState.Checking("0.1.0"))
        assertEquals(UpdateStringKey.CHECKING, p.actionLabel)
        assertEquals(UpdateAction.NONE, p.action)
        assertFalse(p.actionEnabled)
        assertNull(p.badge)
    }

    @Test
    fun upToDate_offersRecheck_withStatus() {
        val p = presentUpdate(UpdateState.UpToDate("0.1.0"))
        assertEquals(UpdateAction.CHECK, p.action)
        assertTrue(p.actionEnabled)
        assertEquals(UpdateStringKey.UP_TO_DATE, p.statusLine)
        assertNull(p.badge)
    }

    @Test
    fun available_offersInstall_andShowsBadge() {
        val p = presentUpdate(UpdateState.Available("0.1.0", "0.2.0"))
        assertEquals(UpdateAction.INSTALL, p.action)
        assertTrue(p.actionEnabled)
        assertEquals(UpdateStringKey.RESTART_TO_UPDATE, p.actionLabel)
        assertEquals(UpdateStringKey.VERSION_AVAILABLE, p.statusLine)
        assertEquals("0.2.0", p.versionArg)
        assertEquals(UpdateStringKey.BADGE_AVAILABLE, p.badge)
    }

    @Test
    fun installing_isInert_butStillBadged() {
        val p = presentUpdate(UpdateState.Installing("0.1.0", "0.2.0"))
        assertEquals(UpdateAction.NONE, p.action)
        assertFalse(p.actionEnabled)
        assertEquals("0.2.0", p.versionArg)
        assertEquals(UpdateStringKey.BADGE_UPDATING, p.badge)
    }

    @Test
    fun failed_offersRetry_withErrorStatus_noBadge() {
        val p = presentUpdate(UpdateState.Failed("0.1.0"))
        assertEquals(UpdateAction.CHECK, p.action)
        assertTrue(p.actionEnabled)
        assertEquals(UpdateStringKey.CHECK_FAILED, p.statusLine)
        assertNull(p.badge)
    }

    @Test
    fun unsupported_linux_pointsToPackageManager() {
        val p = presentUpdate(UpdateState.Unsupported("0.1.0", UnsupportedReason.PACKAGE_MANAGER))
        assertEquals(UpdateAction.VIEW_RELEASES, p.action)
        assertEquals(UpdateStringKey.UNSUPPORTED_PACKAGE_MANAGER, p.statusLine)
        assertNull(p.badge)
    }

    @Test
    fun unsupported_dev_pointsToInstalledApp() {
        val p = presentUpdate(UpdateState.Unsupported("0.1.0", UnsupportedReason.NOT_PACKAGED))
        assertEquals(UpdateAction.VIEW_RELEASES, p.action)
        assertEquals(UpdateStringKey.UNSUPPORTED_NOT_PACKAGED, p.statusLine)
    }
}
