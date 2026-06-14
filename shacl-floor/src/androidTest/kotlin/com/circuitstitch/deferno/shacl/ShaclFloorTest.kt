package com.circuitstitch.deferno.shacl

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device proof that the real libshacl_aio.so loads on the test ABI and the floor extracts a
 * task end-to-end (transcript -> JNA -> Rust -> JSON -> DraftTask). The headless JVM gate can't
 * load the .so, so this is an instrumented test — run via :shacl-floor:connectedAndroidTest.
 */
@OptIn(ExperimentalTime::class)
class ShaclFloorTest {
    @Test
    fun extractsFloorTaskFromTranscript() {
        val tasks = ShaclFloor.extract("buy milk tomorrow", Instant.parse("2026-06-14T12:00:00Z"))

        assertEquals(1, tasks.size)
        assertEquals("buy milk", tasks[0].title)
        assertTrue("expected a resolved completeBy", tasks[0].completeBy != null)
    }
}
