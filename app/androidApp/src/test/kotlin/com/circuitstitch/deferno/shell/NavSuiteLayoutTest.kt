package com.circuitstitch.deferno.shell

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact-only **"More"** overflow split (#70, ADR-0015): on a bottom bar the primary Destinations
 * stay direct and the secondary ones move under "More"; on a rail/drawer every Destination is listed
 * directly with no "More". [navSuiteLayoutFor] is the pure partition the Main shell View reads; this
 * pins it without Robolectric (mirrors [NavigationSuiteTypeTest]).
 */
class NavSuiteLayoutTest {

    @Test
    fun compact_showsPrimaryDestinations_andOverflowsSecondariesUnderMore() {
        val layout = navSuiteLayoutFor(Destination.entries, NavigationSuiteType.NavigationBar)

        assertEquals(listOf(Destination.Plan, Destination.Calendar, Destination.Tasks), layout.items)
        assertEquals(listOf(Destination.Profile, Destination.Settings), layout.overflow)
        assertTrue("a compact bottom bar needs the More overflow", layout.showMore)
    }

    @Test
    fun rail_listsEveryDestinationDirectly_withNoMore() {
        val layout = navSuiteLayoutFor(Destination.entries, NavigationSuiteType.NavigationRail)

        assertEquals(Destination.entries, layout.items)
        assertTrue("rail lists secondaries directly, so nothing overflows", layout.overflow.isEmpty())
        assertFalse(layout.showMore)
    }

    @Test
    fun drawer_listsEveryDestinationDirectly_withNoMore() {
        val layout = navSuiteLayoutFor(Destination.entries, NavigationSuiteType.NavigationDrawer)

        assertEquals(Destination.entries, layout.items)
        assertFalse(layout.showMore)
    }
}
