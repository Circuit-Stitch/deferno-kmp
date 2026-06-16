package com.circuitstitch.deferno.feature.tasks.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The secondary-pane precedence (#67/#227), unit-tested on the JVM fast path (ADR-0006) since the
 * `@Composable` renderers that consume it can't be. Detail is the lone secondary slot since ADR-0034
 * (the Item tree is the primary pane), so the choice is just "detail open or not".
 */
class SecondarySlotTest {

    @Test
    fun detailWhenOpen() {
        assertEquals(SecondarySlot.Detail, resolveSecondarySlot(hasDetail = true))
    }

    @Test
    fun noneWhenNothingOpen() {
        assertEquals(SecondarySlot.None, resolveSecondarySlot(hasDetail = false))
    }
}
