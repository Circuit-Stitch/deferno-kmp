package com.circuitstitch.deferno.feature.plan

import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves this module's commonTest source set compiles and runs on the JVM-fast path. */
class ScaffoldTest {
    @Test
    fun scaffoldModuleResolves() {
        assertEquals("feature:plan", SCAFFOLD)
    }
}
