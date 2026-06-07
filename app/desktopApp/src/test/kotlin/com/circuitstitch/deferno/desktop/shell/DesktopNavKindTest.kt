package com.circuitstitch.deferno.desktop.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The desktop nav suite must adapt by **window width** only — a compact rail vs. an expanded permanent
 * drawer — never by device type (ADR-0008 G1 / ADR-0007). [desktopNavKindFor] is the pure breakpoint
 * mapping the Main shell View reads; this pins the single [DRAWER_MIN_WIDTH_DP] boundary.
 */
class DesktopNavKindTest {

    @Test
    fun narrowWindow_usesRail() {
        assertEquals(DesktopNavKind.Rail, desktopNavKindFor(900))
    }

    @Test
    fun wideWindow_usesDrawer() {
        assertEquals(DesktopNavKind.Drawer, desktopNavKindFor(1600))
    }

    @Test
    fun breakpointBoundary() {
        assertEquals(DesktopNavKind.Rail, desktopNavKindFor(DRAWER_MIN_WIDTH_DP - 1))
        assertEquals(DesktopNavKind.Drawer, desktopNavKindFor(DRAWER_MIN_WIDTH_DP))
    }
}
