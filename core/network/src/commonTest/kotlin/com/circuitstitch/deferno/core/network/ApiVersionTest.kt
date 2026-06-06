package com.circuitstitch.deferno.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `major.minor` parse + ordering and the supported-window check (ADR-0005, issue #17). */
class ApiVersionTest {

    @Test
    fun parsesWellFormedMajorMinor() {
        assertEquals(ApiVersion(0, 1), ApiVersion.parseOrNull("0.1"))
        assertEquals(ApiVersion(12, 30), ApiVersion.parseOrNull("12.30"))
    }

    @Test
    fun rejectsMalformedVersions() {
        assertNull(ApiVersion.parseOrNull("1"))        // missing minor
        assertNull(ApiVersion.parseOrNull("1.2.3"))    // too many components
        assertNull(ApiVersion.parseOrNull("x.y"))      // non-numeric
        assertNull(ApiVersion.parseOrNull("-1.0"))     // negative
        assertNull(ApiVersion.parseOrNull(""))         // empty
    }

    @Test
    fun ordersByMajorThenMinor() {
        assertTrue(ApiVersion(0, 1) < ApiVersion(0, 2))
        assertTrue(ApiVersion(0, 9) < ApiVersion(1, 0))
        assertTrue(ApiVersion(2, 0) > ApiVersion(1, 9))
        assertEquals(ApiVersion(0, 1), ApiVersion(0, 1))
    }

    @Test
    fun rendersAsMajorDotMinor() {
        assertEquals("0.1", ApiVersion(0, 1).toString())
    }

    @Test
    fun supportsOnlyVersionsInsideTheWindow() {
        // Today the window is the single live version 0.1 (MIN == MAX).
        assertTrue(SupportedApiVersions.supports(ApiVersion(0, 1)))
        assertFalse(SupportedApiVersions.supports(ApiVersion(0, 2))) // above MAX
        assertFalse(SupportedApiVersions.supports(ApiVersion(0, 0))) // below MIN
        assertEquals(SupportedApiVersions.MIN, SupportedApiVersions.MAX)
    }
}
