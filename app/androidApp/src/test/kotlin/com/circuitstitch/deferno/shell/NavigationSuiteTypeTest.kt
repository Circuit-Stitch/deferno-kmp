package com.circuitstitch.deferno.shell

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.window.core.layout.WindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The nav suite must adapt by **window size class** only — bottom bar → rail → drawer — never by
 * device type (ADR-0008 G1 / ADR-0007). [navigationSuiteTypeFor] is the pure breakpoint mapping the
 * Main shell View reads; this pins its three bands and their exact 600dp / 840dp boundaries.
 */
class NavigationSuiteTypeTest {

    private fun widthDp(dp: Int): WindowSizeClass = WindowSizeClass(dp, 900)

    @Test
    fun compactWidth_usesBottomBar() {
        assertEquals(NavigationSuiteType.NavigationBar, navigationSuiteTypeFor(widthDp(400)))
    }

    @Test
    fun mediumWidth_usesRail() {
        assertEquals(NavigationSuiteType.NavigationRail, navigationSuiteTypeFor(widthDp(700)))
    }

    @Test
    fun expandedWidth_usesDrawer() {
        assertEquals(NavigationSuiteType.NavigationDrawer, navigationSuiteTypeFor(widthDp(1000)))
    }

    @Test
    fun breakpointBoundaries() {
        assertEquals(NavigationSuiteType.NavigationBar, navigationSuiteTypeFor(widthDp(599)))
        assertEquals(NavigationSuiteType.NavigationRail, navigationSuiteTypeFor(widthDp(600)))
        assertEquals(NavigationSuiteType.NavigationRail, navigationSuiteTypeFor(widthDp(839)))
        assertEquals(NavigationSuiteType.NavigationDrawer, navigationSuiteTypeFor(widthDp(840)))
    }
}
