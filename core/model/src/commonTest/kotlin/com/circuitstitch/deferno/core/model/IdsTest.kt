package com.circuitstitch.deferno.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Contract for the identity value classes: each rejects blank values so a malformed id can never
 * become a reconcile / ownership / identity key (ADR-0001/0002). JVM-fast path (ADR-0006).
 */
class IdsTest {
    @Test
    fun taskIdRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { TaskId("") }
        assertFailsWith<IllegalArgumentException> { TaskId("   ") }
    }

    @Test
    fun taskIdPreservesValue() {
        assertEquals("7033cae7-eff6-4df1-bed9-01d16e89c2b0", TaskId("7033cae7-eff6-4df1-bed9-01d16e89c2b0").value)
    }

    @Test
    fun orgIdRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { OrgId("") }
        assertFailsWith<IllegalArgumentException> { OrgId("  ") }
    }

    @Test
    fun orgIdPreservesValue() {
        assertEquals("ebca93e5-d663-4624-9fe9-c5361b5b4390", OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390").value)
    }

    @Test
    fun userIdRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { UserId("") }
        assertFailsWith<IllegalArgumentException> { UserId("  ") }
    }

    @Test
    fun userIdPreservesValue() {
        assertEquals("1d35f62e-eed9-44de-96e8-e61a307af83f", UserId("1d35f62e-eed9-44de-96e8-e61a307af83f").value)
    }

    @Test
    fun recurringIdsRejectBlank() {
        assertFailsWith<IllegalArgumentException> { HabitId("") }
        assertFailsWith<IllegalArgumentException> { ChoreId("  ") }
        assertFailsWith<IllegalArgumentException> { EventId("") }
        assertFailsWith<IllegalArgumentException> { OccurrenceId("   ") }
    }

    @Test
    fun recurringIdsPreserveValue() {
        assertEquals("h-1", HabitId("h-1").value)
        assertEquals("c-1", ChoreId("c-1").value)
        assertEquals("e-1", EventId("e-1").value)
        assertEquals("o-1", OccurrenceId("o-1").value)
    }

    @Test
    fun itemKindHasTheFourPickerKinds() {
        assertEquals(
            listOf(ItemKind.Task, ItemKind.Habit, ItemKind.Chore, ItemKind.Event),
            ItemKind.entries,
        )
    }
}
